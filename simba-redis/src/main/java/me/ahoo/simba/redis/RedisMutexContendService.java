/*
 * Copyright [2021-2021] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.redis;

import com.google.common.base.Strings;
import com.google.common.io.Resources;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.reactive.RedisScriptingReactiveCommands;
import io.lettuce.core.pubsub.api.reactive.ChannelMessage;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.Simba;
import me.ahoo.simba.SimbaException;
import me.ahoo.simba.core.*;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * @author ahoo wang
 */
@Slf4j
public class RedisMutexContendService extends AbstractMutexContendService {

    private static final byte[] SCRIPT_ACQUIRE = getScript("mutex_acquire.lua");
    private static final byte[] SCRIPT_RELEASE = getScript("mutex_release.lua");
    private static final byte[] SCRIPT_GUARD = getScript("mutex_guard.lua");

    private final String[] keys;
    /**
     * 锁获取成功通道（关联锁）
     * 1. 当有竞争者成功获取到锁时往该通道发送消息
     */
    private final String mutexChannel;
    /**
     * 竞争者的通道（关联竞争者编号）
     * 1. 当尝试竞争锁失败时，将自己加入等待队列
     * 2. 当持有者释放锁时，将选取等待队列中当竞争者发送释放消息
     */
    private final String contenderChannel;
    private final Duration ttl;
    private final Duration transition;
    private final ContendPeriod contendPeriod;
    private final RedisScriptingReactiveCommands<String, String> redisCommands;
    private final RedisPubSubReactiveCommands<String, String> redisPubSubCommands;
    private ChannelSubscriber channelSubscriber;
    private volatile Disposable scheduleFuture;

    protected RedisMutexContendService(MutexContender contender,
                                       Executor handleExecutor,
                                       Duration ttl,
                                       Duration transition,
                                       RedisScriptingReactiveCommands<String, String> redisCommands,
                                       RedisPubSubReactiveCommands<String, String> redisPubSubCommands) {
        super(contender, handleExecutor);
        this.keys = new String[]{"{" + contender.getMutex() + "}"};
        this.mutexChannel = Strings.lenientFormat("%s:{%s}", Simba.SIMBA, contender.getMutex());
        this.contenderChannel = Strings.lenientFormat("%s:%s", mutexChannel, contender.getContenderId());
        this.ttl = ttl;
        this.transition = transition;
        this.redisCommands = redisCommands;
        this.redisPubSubCommands = redisPubSubCommands;
        this.contendPeriod = new ContendPeriod(getContenderId());
    }

    public static byte[] getScript(String scriptName) {
        try {
            URL url = Resources.getResource(scriptName);
            return Resources.toByteArray(url);
        } catch (IOException ioException) {
            if (log.isErrorEnabled()) {
                log.error(ioException.getMessage(), ioException);
            }
            throw new SimbaException(ioException.getMessage(), ioException);
        }
    }

    /**
     * 1. 开始订阅
     * 1.1 本地订阅
     * 1.2 远程订阅
     * 2. 尝试竞争
     * 3. 开始守护
     */
    @Override
    protected void startContend() {
        startSubscribe();
        nextSchedule(0);
    }

    /**
     * 开始订阅
     */
    private void startSubscribe() {
        channelSubscriber = new ChannelSubscriber();
        redisPubSubCommands.observeChannels().subscribe(channelSubscriber);
        redisPubSubCommands.subscribe(this.mutexChannel, this.contenderChannel);
    }

    private void nextSchedule(long nextDelay) {
        if (log.isDebugEnabled()) {
            log.debug("nextSchedule - mutex:[{}] contenderId:[{}] - nextDelay:[{}].", getMutex(), getContenderId(), nextDelay);
        }
        scheduleFuture = Mono.delay(Duration.ofMillis(nextDelay))
                .flatMap(tick -> {
                    if (isOwner()) {
                        return guard();
                    }
                    return acquire();
                })
                .subscribe();
    }

    private MutexOwner notifyOwnerAndScheduleNext(String resultStr) {
        try {
            AcquireResult result = AcquireResult.of(resultStr);
            final MutexOwner mutexOwner = newMutexOwner(result);
            notifyOwner(mutexOwner);
            long nextDelay = contendPeriod.ensureNextDelay(mutexOwner);
            nextSchedule(nextDelay);
            return mutexOwner;
        } catch (Throwable throwable) {
            if (log.isErrorEnabled()) {
                log.error(throwable.getMessage(), throwable);
            }
            nextSchedule(ttl.toMillis());
            return MutexOwner.NONE;
        }
    }

    private Mono<MutexOwner> guard() {
        String[] values = {getContenderId(), String.valueOf(ttl.toMillis())};
        return redisCommands.eval(SCRIPT_GUARD, ScriptOutputType.VALUE, keys, values)
                .next()
                .cast(String.class)
                .map(this::notifyOwnerAndScheduleNext);
    }

    private Mono<MutexOwner> acquire() {
        String[] values = {getContenderId(), String.valueOf(ttl.toMillis() + transition.toMillis())};
        return redisCommands.eval(SCRIPT_ACQUIRE, ScriptOutputType.VALUE, keys, values)
                .next()
                .cast(String.class)
                .map(this::notifyOwnerAndScheduleNext);
    }

    private MutexOwner newMutexOwner(AcquireResult result) {
        return newMutexOwner(result.getOwnerId(), result.getTransitionAt());
    }

    private MutexOwner newMutexOwner(String ownerId, long transitionAt) {
        long ttlAt = transitionAt - transition.toMillis();
        long acquiredAt = ttlAt - ttl.toMillis();
        return new MutexOwner(ownerId, acquiredAt, ttlAt, transitionAt);
    }

    private long getTransitionAt(Message message) {
        return message.getEventAt() + ttl.toMillis() + transition.toMillis();
    }

    /**
     * 1. 取消订阅
     * 2. 关闭定时调度
     * 3.
     */
    @Override
    protected void stopContend() {
        stopSubscribe();
        disposeSchedule();
        release();
    }

    /**
     * 停止订阅
     */
    private void stopSubscribe() {
        if (channelSubscriber != null) {
            channelSubscriber.cancel();
        }
        redisPubSubCommands.unsubscribe(this.mutexChannel, this.contenderChannel).subscribe();
    }

    private void disposeSchedule() {
        if (scheduleFuture == null || scheduleFuture.isDisposed()) {
            return;
        }
        this.scheduleFuture.dispose();
    }

    private void release() {
        String[] values = {getContenderId()};
        redisCommands.eval(SCRIPT_RELEASE, ScriptOutputType.BOOLEAN, keys, values)
                .next()
                .ofType(Boolean.class)
                .doOnNext(succeed -> {
                    try {
                        notifyOwner(MutexOwner.NONE);
                    } catch (Throwable throwable) {
                        if (log.isWarnEnabled()) {
                            log.warn("stopContend - mutex:[{}] - contenderId:[{}] - message:[{}]", getMutex(), getContenderId(), throwable.getMessage());
                        }
                    }
                })
                .subscribe();
    }

    public class ChannelSubscriber extends BaseSubscriber<ChannelMessage<String, String>> {

        @Override
        protected void hookOnNext(ChannelMessage<String, String> value) {
            boolean subscribed = value.getChannel().startsWith(mutexChannel);
            if (log.isDebugEnabled()) {
                log.debug("hookOnNext - mutex:[{}] - ownerId:[{}] - channel:[{}] - message:[{}] - subscribed:[{}]", getMutex(), getContenderId(), value.getChannel(), value.getMessage(), subscribed);
            }
            if (!subscribed) {
                return;
            }
            try {
                Message message = Message.of(value.getMessage());
                switch (message.getEvent()) {
                    case Message.EVENT_RELEASED: {
                        notifyOwner(MutexOwner.NONE);
                        acquire().subscribe();
                        break;
                    }
                    case Message.EVENT_ACQUIRED: {
                        notifyOwner(newMutexOwner(message.getOwnerId(), getTransitionAt(message)));
                        break;
                    }
                    default:
                        throw new IllegalStateException("Unexpected value: " + message.getEvent());
                }
            } catch (Throwable throwable) {
                if (log.isErrorEnabled()) {
                    log.error(throwable.getMessage(), throwable);
                }
            }
        }
    }
}
