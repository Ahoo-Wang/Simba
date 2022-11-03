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
package me.ahoo.simba.spring.redis

import com.google.common.base.Strings
import com.google.common.collect.Lists
import lombok.extern.slf4j.Slf4j
import me.ahoo.simba.Simba
import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.ContendPeriod
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Spring Redis Mutex Contend Service.
 *
 * @author ahoo wang
 */
@Slf4j
class SpringRedisMutexContendService(
    contender: MutexContender,
    handleExecutor: Executor,
    ttl: Duration,
    transition: Duration,
    redisTemplate: StringRedisTemplate,
    listenerContainer: RedisMessageListenerContainer,
    scheduledExecutorService: ScheduledExecutorService
) : AbstractMutexContendService(contender, handleExecutor) {
    companion object {
        private val log = LoggerFactory.getLogger(SpringRedisMutexContendService::class.java)
        private val ACQUIRE_RESOURCE: Resource = ClassPathResource("mutex_acquire.lua")
        private val SCRIPT_ACQUIRE = RedisScript.of(ACQUIRE_RESOURCE, String::class.java)
        private val RELEASE_RESOURCE: Resource = ClassPathResource("mutex_release.lua")
        private val SCRIPT_RELEASE = RedisScript.of(RELEASE_RESOURCE, Boolean::class.java)
        private val GUARD_RESOURCE: Resource = ClassPathResource("mutex_guard.lua")
        private val SCRIPT_GUARD = RedisScript.of(GUARD_RESOURCE, String::class.java)
    }

    private val keys: List<String>

    /**
     * 锁获取成功通道（关联锁）.
     * 1. 当有竞争者成功获取到锁时往该通道发送消息
     */
    private val mutexChannel: String

    /**
     * 竞争者的通道（关联竞争者编号）.
     * <pre>
     * 1. 当尝试竞争锁失败时，将自己加入等待队列
     * 2. 当持有者释放锁时，将选取等待队列中当竞争者发送释放消息
    </pre> *
     */
    private val contenderChannel: String
    private val listenTopics: List<ChannelTopic>
    private val ttl: Duration
    private val transition: Duration
    private val contendPeriod: ContendPeriod
    private val redisTemplate: StringRedisTemplate
    private val listenerContainer: RedisMessageListenerContainer
    private val mutexMessageListener: MutexMessageListener
    private val scheduledExecutorService: ScheduledExecutorService
    private var scheduleFuture: ScheduledFuture<MutexOwner>? = null

    init {
        keys = Lists.newArrayList("{" + contender.mutex + "}")
        mutexChannel = Strings.lenientFormat("%s:{%s}", Simba.SIMBA, contender.mutex)
        contenderChannel = Strings.lenientFormat("%s:%s", mutexChannel, contender.contenderId)
        this.scheduledExecutorService = scheduledExecutorService
        listenTopics = listOf(ChannelTopic(mutexChannel), ChannelTopic(contenderChannel))
        this.ttl = ttl
        this.transition = transition
        this.redisTemplate = redisTemplate
        this.listenerContainer = listenerContainer
        contendPeriod = ContendPeriod(contenderId)
        mutexMessageListener = MutexMessageListener()
    }

    /**
     * <pre>
     * 1. 开始订阅
     * 1.1 本地订阅
     * 1.2 远程订阅
     * 2. 尝试竞争
     * 3. 开始守护
    </pre> *
     */
    override fun startContend() {
        startSubscribe()
        nextSchedule(0)
    }

    /**
     * 开始订阅.
     */
    private fun startSubscribe() {
        listenerContainer.addMessageListener(mutexMessageListener, listenTopics)
    }

    private fun nextSchedule(nextDelay: Long) {
        if (log.isDebugEnabled) {
            log.debug(
                "nextSchedule - mutex:[{}] contenderId:[{}] - nextDelay:[{}].",
                mutex,
                contenderId,
                nextDelay
            )
        }
        scheduleFuture = scheduledExecutorService.schedule<MutexOwner>({
            if (isOwner) {
                return@schedule guard()
            }
            acquire()
        }, nextDelay, TimeUnit.MILLISECONDS)
    }

    private fun notifyOwnerAndScheduleNext(resultStr: String): MutexOwner {
        return try {
            val result: AcquireResult = AcquireResult.Companion.of(resultStr)
            val mutexOwner = newMutexOwner(result)
            notifyOwner(mutexOwner)
            val nextDelay = contendPeriod.ensureNextDelay(mutexOwner)
            nextSchedule(nextDelay)
            mutexOwner
        } catch (throwable: Throwable) {
            if (log.isErrorEnabled) {
                log.error(throwable.message, throwable)
            }
            nextSchedule(ttl.toMillis())
            MutexOwner.NONE
        }
    }

    private fun guard(): MutexOwner {
        val message = redisTemplate.execute(SCRIPT_GUARD, keys, contenderId, ttl.toMillis().toString())
        if (log.isDebugEnabled) {
            log.debug(
                "guard - mutex:[{}] contenderId:[{}] - message:[{}].",
                mutex,
                contenderId,
                message
            )
        }
        return notifyOwnerAndScheduleNext(message)
    }

    private fun acquire(): MutexOwner {
        val message = redisTemplate.execute(
            SCRIPT_ACQUIRE,
            keys,
            contenderId,
            (ttl.toMillis() + transition.toMillis()).toString()
        )
        if (log.isDebugEnabled) {
            log.debug(
                "acquire - mutex:[{}] contenderId:[{}] - message:[{}].",
                mutex,
                contenderId,
                message
            )
        }
        return notifyOwnerAndScheduleNext(message)
    }

    private fun newMutexOwner(result: AcquireResult): MutexOwner {
        return newMutexOwner(result.ownerId, result.transitionAt)
    }

    private fun newMutexOwner(ownerId: String?, transitionAt: Long): MutexOwner {
        val ttlAt = transitionAt - transition.toMillis()
        val acquiredAt = ttlAt - ttl.toMillis()
        return MutexOwner(ownerId!!, acquiredAt, ttlAt, transitionAt)
    }

    private fun getTransitionAt(message: OwnerEvent): Long {
        return message.eventAt + ttl.toMillis() + transition.toMillis()
    }

    /**
     * 1. 取消订阅
     * 2. 关闭定时调度
     * 3.
     */
    override fun stopContend() {
        stopSubscribe()
        disposeSchedule()
        release()
    }

    /**
     * 停止订阅.
     */
    private fun stopSubscribe() {
        listenerContainer.removeMessageListener(mutexMessageListener, listenTopics)
    }

    private fun disposeSchedule() {
        scheduleFuture?.cancel(true)
    }

    private fun release() {
        val succeed = redisTemplate.execute(SCRIPT_RELEASE, keys, contenderId)
        if (log.isDebugEnabled) {
            log.debug(
                "release - mutex:[{}] - contenderId:[{}] - succeed:[{}]",
                mutex,
                contenderId,
                succeed
            )
        }
        try {
            notifyOwner(MutexOwner.NONE)
        } catch (throwable: Throwable) {
            if (log.isWarnEnabled) {
                log.warn(
                    "release - mutex:[{}] - contenderId:[{}] - message:[{}]",
                    mutex,
                    contenderId,
                    throwable.message
                )
            }
        }
    }

    inner class MutexMessageListener : MessageListener {
        override fun onMessage(message: Message, pattern: ByteArray?) {
            val channel = String(message.channel, StandardCharsets.UTF_8)
            val body = String(message.body, StandardCharsets.UTF_8)
            if (log.isDebugEnabled) {
                log.debug(
                    "onMessage - mutex:[{}] - contenderId:[{}] - channel:[{}] - message:[{}].",
                    mutex,
                    contenderId,
                    channel,
                    body
                )
            }
            val ownerEvent: OwnerEvent = OwnerEvent.of(body)
            when (ownerEvent.event) {
                OwnerEvent.EVENT_RELEASED -> {
                    notifyOwner(MutexOwner.NONE)
                    acquire()
                }

                OwnerEvent.EVENT_ACQUIRED -> {
                    notifyOwner(newMutexOwner(ownerEvent.ownerId, getTransitionAt(ownerEvent)))
                }

                else -> throw IllegalStateException("Unexpected value: " + ownerEvent.event)
            }
        }
    }
}
