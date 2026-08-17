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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexState
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpringRedisMutexContendServiceLeaseTest {
    @Test
    fun `renewal failure revokes local ownership`() {
        val acquired = CountDownLatch(1)
        val released = CountDownLatch(1)
        val contender = object : AbstractMutexContender("lease-expiry", "lease-owner") {
            override fun onAcquired(mutexState: MutexState) {
                acquired.countDown()
            }

            override fun onReleased(mutexState: MutexState) {
                released.countDown()
            }
        }
        val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
        every {
            redisTemplate.execute(
                match<RedisScript<String>> { it.resultType == String::class.java },
                any<List<String>>(),
                *anyVararg()
            )
        } returns "${contender.contenderId}@@100" andThenThrows
            RedisConnectionFailureException("unavailable")
        every {
            redisTemplate.execute(
                match<RedisScript<Boolean>> { it.resultType == Boolean::class.java },
                any<List<String>>(),
                *anyVararg()
            )
        } returns true
        val scheduler = ScheduledThreadPoolExecutor(1)
        val service = SpringRedisMutexContendService(
            contender,
            Runnable::run,
            Duration.ofMillis(50),
            Duration.ofMillis(50),
            redisTemplate,
            mockk<RedisMessageListenerContainer>(relaxed = true),
            scheduler
        )

        try {
            service.start()
            assertThat("initial acquisition should succeed", acquired.await(1, TimeUnit.SECONDS))
            assertThat("renewal failure should revoke ownership", released.await(300, TimeUnit.MILLISECONDS))
            assertThat(service.isOwner, equalTo(false))
        } finally {
            if (service.running) {
                service.stop()
            }
            scheduler.shutdownNow()
        }
    }

    @Suppress("LongMethod")
    @Test
    fun `stale failure does not revoke restarted lifecycle ownership`() {
        val firstInvoked = CountDownLatch(1)
        val unblockFirst = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val released = AtomicInteger()
        val calls = AtomicInteger()
        val contender = object : AbstractMutexContender("restart-lease", "lease-owner") {
            override fun onAcquired(mutexState: MutexState) {
                acquired.countDown()
            }

            override fun onReleased(mutexState: MutexState) {
                released.incrementAndGet()
            }
        }
        val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
        every {
            redisTemplate.execute(
                match<RedisScript<String>> { it.resultType == String::class.java },
                any<List<String>>(),
                *anyVararg()
            )
        } answers {
            if (calls.getAndIncrement() == 0) {
                firstInvoked.countDown()
                while (unblockFirst.count > 0) {
                    try {
                        unblockFirst.await()
                    } catch (_: InterruptedException) {
                    }
                }
                throw RedisConnectionFailureException("stale failure")
            }
            "${contender.contenderId}@@${Long.MAX_VALUE}"
        }
        every {
            redisTemplate.execute(
                match<RedisScript<Boolean>> { it.resultType == Boolean::class.java },
                any<List<String>>(),
                *anyVararg()
            )
        } returns true
        val scheduler = CompletionTrackingScheduler()
        val service = SpringRedisMutexContendService(
            contender,
            Runnable::run,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            redisTemplate,
            mockk<RedisMessageListenerContainer>(relaxed = true),
            scheduler
        )

        try {
            service.start()
            assertThat("first Redis call should be in flight", firstInvoked.await(1, TimeUnit.SECONDS))
            service.stop()
            service.start()
            assertThat("restarted lifecycle should acquire", acquired.await(1, TimeUnit.SECONDS))

            unblockFirst.countDown()
            assertThat("both lifecycle tasks should finish", scheduler.completed.await(2, TimeUnit.SECONDS))

            assertThat(service.isOwner, equalTo(true))
            assertThat(released.get(), equalTo(0))
        } finally {
            unblockFirst.countDown()
            if (service.running) {
                service.stop()
            }
            scheduler.shutdownNow()
        }
    }

    private class CompletionTrackingScheduler : ScheduledThreadPoolExecutor(2) {
        val completed = CountDownLatch(2)

        override fun afterExecute(runnable: Runnable, throwable: Throwable?) {
            super.afterExecute(runnable, throwable)
            completed.countDown()
        }
    }
}
