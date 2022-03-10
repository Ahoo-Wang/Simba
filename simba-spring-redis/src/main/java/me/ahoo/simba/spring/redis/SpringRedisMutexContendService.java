/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

package me.ahoo.simba.spring.redis;

import me.ahoo.simba.Simba;
import me.ahoo.simba.core.AbstractMutexContendService;
import me.ahoo.simba.core.ContendPeriod;
import me.ahoo.simba.core.MutexContender;
import me.ahoo.simba.core.MutexOwner;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spring Redis Mutex Contend Service.
 *
 * @author ahoo wang
 */
@Slf4j
public class SpringRedisMutexContendService extends AbstractMutexContendService {
    
    private static final Resource ACQUIRE_RESOURCE = new ClassPathResource("mutex_acquire.lua");
    private static final RedisScript<String> SCRIPT_ACQUIRE = RedisScript.of(ACQUIRE_RESOURCE, String.class);
    private static final Resource RELEASE_RESOURCE = new ClassPathResource("mutex_release.lua");
    private static final RedisScript<Boolean> SCRIPT_RELEASE = RedisScript.of(RELEASE_RESOURCE, Boolean.class);
    private static final Resource GUARD_RESOURCE = new ClassPathResource("mutex_guard.lua");
    private static final RedisScript<String> SCRIPT_GUARD = RedisScript.of(GUARD_RESOURCE, String.class);
    
    private final List<String> keys;
    /**
     * 锁获取成功通道（关联锁）.
     * 1. 当有竞争者成功获取到锁时往该通道发送消息
     */
    private final String mutexChannel;
    /**
     * 竞争者的通道（关联竞争者编号）.
     * <pre>
     *  1. 当尝试竞争锁失败时，将自己加入等待队列
     *  2. 当持有者释放锁时，将选取等待队列中当竞争者发送释放消息
     * </pre>
     */
    private final String contenderChannel;
    private final List<ChannelTopic> listenTopics;
    private final Duration ttl;
    private final Duration transition;
    private final ContendPeriod contendPeriod;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final MutexMessageListener mutexMessageListener;
    private final ScheduledExecutorService scheduledExecutorService;
    private ScheduledFuture<MutexOwner> scheduleFuture;
    
    protected SpringRedisMutexContendService(MutexContender contender,
                                             Executor handleExecutor,
                                             Duration ttl,
                                             Duration transition,
                                             StringRedisTemplate redisTemplate,
                                             RedisMessageListenerContainer listenerContainer,
                                             ScheduledExecutorService scheduledExecutorService) {
        super(contender, handleExecutor);
        this.keys = Lists.newArrayList("{" + contender.getMutex() + "}");
        this.mutexChannel = Strings.lenientFormat("%s:{%s}", Simba.SIMBA, contender.getMutex());
        this.contenderChannel = Strings.lenientFormat("%s:%s", mutexChannel, contender.getContenderId());
        this.scheduledExecutorService = scheduledExecutorService;
        this.listenTopics = Arrays.asList(new ChannelTopic(mutexChannel), new ChannelTopic(contenderChannel));
        this.ttl = ttl;
        this.transition = transition;
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.contendPeriod = new ContendPeriod(getContenderId());
        this.mutexMessageListener = new MutexMessageListener();
    }
    
    /**
     * <pre>
     * 1. 开始订阅
     * 1.1 本地订阅
     * 1.2 远程订阅
     * 2. 尝试竞争
     * 3. 开始守护
     * </pre>
     */
    @Override
    protected void startContend() {
        startSubscribe();
        nextSchedule(0);
    }
    
    /**
     * 开始订阅.
     */
    private void startSubscribe() {
        listenerContainer.addMessageListener(mutexMessageListener, listenTopics);
    }
    
    private void nextSchedule(long nextDelay) {
        if (log.isDebugEnabled()) {
            log.debug("nextSchedule - mutex:[{}] contenderId:[{}] - nextDelay:[{}].", getMutex(), getContenderId(), nextDelay);
        }
        scheduleFuture = scheduledExecutorService.schedule(() -> {
            if (isOwner()) {
                return guard();
            }
            return acquire();
        }, nextDelay, TimeUnit.MILLISECONDS);
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
    
    private MutexOwner guard() {
        String message = redisTemplate.execute(SCRIPT_GUARD, keys, getContenderId(), String.valueOf(ttl.toMillis()));
        return notifyOwnerAndScheduleNext(message);
    }
    
    private MutexOwner acquire() {
        String message = redisTemplate.execute(SCRIPT_ACQUIRE, keys, getContenderId(), String.valueOf(ttl.toMillis() + transition.toMillis()));
        return notifyOwnerAndScheduleNext(message);
    }
    
    private MutexOwner newMutexOwner(AcquireResult result) {
        return newMutexOwner(result.getOwnerId(), result.getTransitionAt());
    }
    
    private MutexOwner newMutexOwner(String ownerId, long transitionAt) {
        long ttlAt = transitionAt - transition.toMillis();
        long acquiredAt = ttlAt - ttl.toMillis();
        return new MutexOwner(ownerId, acquiredAt, ttlAt, transitionAt);
    }
    
    private long getTransitionAt(OwnerEvent message) {
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
     * 停止订阅.
     */
    private void stopSubscribe() {
        listenerContainer.removeMessageListener(mutexMessageListener, listenTopics);
    }
    
    private void disposeSchedule() {
        if (scheduleFuture == null || scheduleFuture.isDone()) {
            return;
        }
        this.scheduleFuture.cancel(true);
    }
    
    private void release() {
        redisTemplate.execute(SCRIPT_RELEASE, keys, getContenderId());
        try {
            notifyOwner(MutexOwner.NONE);
        } catch (Throwable throwable) {
            if (log.isWarnEnabled()) {
                log.warn("stopContend - mutex:[{}] - contenderId:[{}] - message:[{}]", getMutex(), getContenderId(), throwable.getMessage());
            }
        }
    }
    
    public class MutexMessageListener implements MessageListener {
        
        @Override
        public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("onMessage - mutex:[{}] - ownerId:[{}] - channel:[{}] - message:[{}].", getMutex(), getContenderId(), channel, body);
            }
            OwnerEvent ownerEvent = OwnerEvent.of(body);
            switch (ownerEvent.getEvent()) {
                case OwnerEvent.EVENT_RELEASED: {
                    notifyOwner(MutexOwner.NONE);
                    acquire();
                    break;
                }
                case OwnerEvent.EVENT_ACQUIRED: {
                    notifyOwner(newMutexOwner(ownerEvent.getOwnerId(), getTransitionAt(ownerEvent)));
                    break;
                }
                default:
                    throw new IllegalStateException("Unexpected value: " + ownerEvent.getEvent());
            }
        }
    }
}
