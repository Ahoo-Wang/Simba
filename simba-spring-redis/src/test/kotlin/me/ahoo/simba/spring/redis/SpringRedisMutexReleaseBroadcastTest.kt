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

import io.lettuce.core.api.async.RedisAsyncCommands
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.MutexState
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Script contract and release hand-off against a real Redis on localhost:6379.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringRedisMutexReleaseBroadcastTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var listenerContainer: RedisMessageListenerContainer
    private val config = LeaseConfig(Duration.ofSeconds(10), Duration.ofSeconds(6))

    @BeforeAll
    fun setup() {
        connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration())
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        listenerContainer = RedisMessageListenerContainer()
        listenerContainer.setConnectionFactory(connectionFactory)
        listenerContainer.afterPropertiesSet()
        listenerContainer.start()
    }

    @AfterAll
    fun destroy() {
        listenerContainer.stop()
        connectionFactory.destroy()
    }

    @Test
    fun `scripts report owner and remaining lease`() {
        val store = SpringRedisMutexLeaseStore(redisTemplate)
        val mutex = "broadcast-scripts"
        redisTemplate.delete(RedisMutexKeys(mutex).mutexKey)

        store.contend(mutex, "a", renew = false, config = config).ownerId.assert().isEqualTo("a")
        val observed = store.contend(mutex, "b", renew = false, config = config)
        observed.ownerId.assert().isEqualTo("a")
        (observed.transitionAt - System.currentTimeMillis()).assert().isBetween(15_000, 16_000)
        store.contend(mutex, "b", renew = true, config = config).ownerId.assert().isEqualTo("a")
        store.contend(mutex, "a", renew = true, config = config).ownerId.assert().isEqualTo("a")

        store.release(mutex, "b").assert().isFalse()
        store.release(mutex, "a").assert().isTrue()
        store.contend(mutex, "b", renew = true, config = config).ownerId.assert().isEmpty()
    }

    @Test
    fun `release broadcasts on the mutex channel and drops the legacy queue`() {
        val store = SpringRedisMutexLeaseStore(redisTemplate)
        val keys = RedisMutexKeys("broadcast-channel")
        redisTemplate.delete(keys.mutexKey)
        redisTemplate.opsForZSet().add(keys.legacyQueueKey, "dead-contender", 1.0)
        val messages = LinkedBlockingQueue<String>()
        val listener = MessageListener { message, _ -> messages.add(String(message.body)) }
        listenerContainer.addMessageListener(listener, ChannelTopic(keys.mutexKey))
        try {
            awaitSubscribed(keys.mutexKey)
            store.contend("broadcast-channel", "a", renew = false, config = config)
            messages.poll(2, TimeUnit.SECONDS).assert().isEqualTo("acquired@@a")

            store.release("broadcast-channel", "a")

            messages.poll(2, TimeUnit.SECONDS).assert().isEqualTo("released@@a")
            redisTemplate.hasKey(keys.legacyQueueKey).assert().isFalse()
        } finally {
            listenerContainer.removeMessageListener(listener)
        }
    }

    @Test
    fun `waiting contender takes over right after the owner stops`() {
        val mutex = "broadcast-handoff"
        redisTemplate.delete(RedisMutexKeys(mutex).mutexKey)
        val factory = SpringRedisMutexContendServiceFactory(
            ttl = config.ttl,
            transition = config.transition,
            redisTemplate = redisTemplate,
            listenerContainer = listenerContainer
        )
        val ownerAcquired = CountDownLatch(1)
        val waiterAcquired = CountDownLatch(1)
        val owner = factory.createMutexContendService(latchContender(mutex, "owner", ownerAcquired))
        val waiter = factory.createMutexContendService(latchContender(mutex, "waiter", waiterAcquired))
        try {
            owner.start()
            ownerAcquired.await(2, TimeUnit.SECONDS).assert().isTrue()
            // The owner already subscribed the shared container to this channel, so the waiter's listener
            // receives broadcasts as soon as start() registers it.
            waiter.start()

            owner.stop()

            // Far below ttl + transition (16s): the broadcast, not polling, triggered the hand-off.
            waiterAcquired.await(3, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            owner.close()
            waiter.close()
            factory.close()
        }
    }

    @Test
    fun `fencing token increases per term and stays stable across renewals`() {
        val store = SpringRedisMutexLeaseStore(redisTemplate)
        val mutex = "fencing-terms"
        val keys = RedisMutexKeys(mutex)
        redisTemplate.delete(listOf(keys.mutexKey, keys.fenceKey, keys.tokenKey))

        val first = store.contend(mutex, "a", renew = false, config = config).fencingToken
        store.contend(mutex, "a", renew = true, config = config).fencingToken.assert().isEqualTo(first)
        store.contend(mutex, "b", renew = false, config = config).fencingToken.assert().isEqualTo(first)
        store.release(mutex, "a").assert().isTrue()
        redisTemplate.hasKey(keys.tokenKey).assert().isFalse()

        val second = store.contend(mutex, "b", renew = false, config = config).fencingToken

        first.assert().isGreaterThan(0)
        second.assert().isEqualTo(first + 1)
        store.release(mutex, "b")
    }

    @Test
    fun `owner exposes the fencing token issued by Redis`() {
        val mutex = "fencing-owner-view"
        val keys = RedisMutexKeys(mutex)
        redisTemplate.delete(listOf(keys.mutexKey, keys.fenceKey, keys.tokenKey))
        val factory = SpringRedisMutexContendServiceFactory(
            ttl = config.ttl,
            transition = config.transition,
            redisTemplate = redisTemplate,
            listenerContainer = listenerContainer
        )
        val acquired = CountDownLatch(1)
        val service = factory.createMutexContendService(latchContender(mutex, "owner", acquired))
        try {
            service.start()
            acquired.await(2, TimeUnit.SECONDS).assert().isTrue()

            service.fencingToken.assert().isEqualTo(redisTemplate.opsForValue().get(keys.tokenKey)!!.toLong())
            service.fencingToken.assert().isGreaterThan(0)
        } finally {
            service.close()
            factory.close()
        }
    }

    private fun latchContender(mutex: String, id: String, acquired: CountDownLatch) =
        object : AbstractMutexContender(mutex, id) {
            override fun onAcquired(mutexState: MutexState) {
                acquired.countDown()
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun awaitSubscribed(channel: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        connectionFactory.connection.use { connection ->
            val commands = connection.nativeConnection as RedisAsyncCommands<ByteArray, ByteArray>
            while (System.nanoTime() < deadline) {
                val counts = commands.pubsubNumsub(channel.toByteArray()).get(1, TimeUnit.SECONDS)
                if ((counts.values.firstOrNull() ?: 0) > 0) {
                    return
                }
                Thread.onSpinWait()
            }
        }
        error("channel [$channel] has no subscriber")
    }
}
