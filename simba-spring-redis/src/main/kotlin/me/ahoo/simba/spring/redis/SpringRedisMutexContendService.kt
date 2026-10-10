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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.LeaseContendService
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/**
 * Spring Redis Mutex Contend Service.
 *
 * Contends through [SpringRedisMutexLeaseStore] and subscribes to owner events while running:
 * acquisitions on the mutex channel update the observed owner, and a release broadcast on it triggers an
 * immediate contention.
 *
 * @author ahoo wang
 */
@Suppress("LongParameterList")
class SpringRedisMutexContendService @JvmOverloads constructor(
    contender: MutexContender,
    handleExecutor: Executor,
    ttl: Duration,
    transition: Duration,
    redisTemplate: StringRedisTemplate,
    private val listenerContainer: RedisMessageListenerContainer,
    scheduledExecutorService: ScheduledExecutorService,
    ioExecutor: Executor = Executor { it.run() },
    observer: ContendObserver = ContendObserver.NOOP
) : LeaseContendService(
    contender = contender,
    handleExecutor = handleExecutor,
    leaseStore = SpringRedisMutexLeaseStore(redisTemplate),
    leaseConfig = LeaseConfig(ttl, transition),
    scheduler = scheduledExecutorService,
    ioExecutor = ioExecutor,
    observer = observer
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val redisKeys = RedisMutexKeys(contender.mutex)
    private val listenTopics: List<ChannelTopic> = listOf(ChannelTopic(redisKeys.mutexKey))
    private val mutexMessageListener: MutexMessageListener = MutexMessageListener()

    override fun onStart() {
        listenerContainer.addMessageListener(mutexMessageListener, listenTopics)
    }

    override fun onStop() {
        listenerContainer.removeMessageListener(mutexMessageListener, listenTopics)
    }

    inner class MutexMessageListener : MessageListener {
        override fun onMessage(message: Message, pattern: ByteArray?) {
            if (!status.isActive) {
                log.warn {
                    "onMessage - ignore - mutex:[$mutex] contenderId:[$contenderId] is not active[$status]."
                }
                return
            }
            val channel = String(message.channel, StandardCharsets.UTF_8)
            val body = String(message.body, StandardCharsets.UTF_8)
            log.debug {
                "onMessage - mutex:[$mutex] - contenderId:[$contenderId] - channel:[$channel] - message:[$body]."
            }
            val ownerEvent: OwnerEvent = OwnerEvent.of(body)
            when (ownerEvent.event) {
                OwnerEvent.EVENT_RELEASED -> {
                    notifyOwner(MutexOwner.NONE)
                    contendNow()
                }

                OwnerEvent.EVENT_ACQUIRED -> {
                    if (ownerEvent.ownerId == contenderId) {
                        /*
                         * Our own acquisition is applied from the script reply, which carries the fencing token;
                         * the broadcast cannot (older nodes parse exactly two fields) and would replace it.
                         */
                        return
                    }
                    val transitionAt = ownerEvent.eventAt + leaseConfig.leaseMillis
                    notifyOwner(leaseOwner(ownerEvent.ownerId, transitionAt, leaseConfig))
                }

                else -> throw IllegalStateException("Unexpected value: " + ownerEvent.event)
            }
        }
    }
}
