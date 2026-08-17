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
import io.mockk.verify
import me.ahoo.simba.core.AbstractMutexContender
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.DefaultMessage
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpringRedisMutexContendServiceSchedulingTest {
    @Test
    fun `guard renews ttl plus transition`() {
        val contender = object : AbstractMutexContender("guard-lease", "guard-owner") {}
        val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
        every {
            redisTemplate.execute(
                match<RedisScript<String>> { it.resultType == String::class.java },
                any<List<String>>(),
                *anyVararg()
            )
        } returns "${contender.contenderId}@@15000"
        val scheduler = ManualScheduledExecutor()
        val service = newService(contender, redisTemplate, scheduler)

        service.start()
        scheduler.run(0)
        scheduler.run(1)

        verify(exactly = 2) {
            redisTemplate.execute(
                match<RedisScript<String>> { it.resultType == String::class.java },
                listOf("{guard-lease}"),
                contender.contenderId,
                "15000"
            )
        }
        scheduler.shutdownNow()
    }

    @Test
    fun `released event replaces the pending retry`() {
        val contender = object : AbstractMutexContender("released", "released-owner") {}
        val redisTemplate = stringRedisTemplateReturning("other@@15000")
        val scheduler = ManualScheduledExecutor()
        val service = newService(contender, redisTemplate, scheduler)

        service.start()
        scheduler.run(0)
        service.MutexMessageListener().onMessage(
            DefaultMessage(
                "simba:{released}:${contender.contenderId}".toByteArray(),
                "released@@other".toByteArray()
            ),
            null
        )

        assertThat(scheduler.pendingTaskCount, equalTo(1))
        scheduler.shutdownNow()
    }

    @Test
    fun `stop prevents a superseded future from acquiring`() {
        val contender = object : AbstractMutexContender("stopped", "stopped-owner") {}
        val acquireCalls = AtomicInteger()
        val redisTemplate = stringRedisTemplateReturning("other@@15000", acquireCalls)
        every {
            redisTemplate.execute(
                match<RedisScript<Boolean>> { it.resultType == Boolean::class.java },
                any<List<String>>(),
                *anyVararg()
            )
        } returns true
        val scheduler = ManualScheduledExecutor()
        val service = newService(contender, redisTemplate, scheduler)

        service.start()
        scheduler.run(0)
        service.MutexMessageListener().onMessage(
            DefaultMessage(
                "simba:{stopped}:${contender.contenderId}".toByteArray(),
                "released@@other".toByteArray()
            ),
            null
        )
        val callsBeforeStop = acquireCalls.get()

        service.stop()
        scheduler.runActive()

        assertThat(acquireCalls.get(), equalTo(callsBeforeStop))
        scheduler.shutdownNow()
    }

    private fun stringRedisTemplateReturning(
        result: String,
        calls: AtomicInteger = AtomicInteger()
    ): StringRedisTemplate {
        return mockk<StringRedisTemplate>(relaxed = true).also { redisTemplate ->
            every {
                redisTemplate.execute(
                    match<RedisScript<String>> { it.resultType == String::class.java },
                    any<List<String>>(),
                    *anyVararg()
                )
            } answers {
                calls.incrementAndGet()
                result
            }
        }
    }

    private fun newService(
        contender: AbstractMutexContender,
        redisTemplate: StringRedisTemplate,
        scheduler: ScheduledThreadPoolExecutor
    ): SpringRedisMutexContendService {
        return SpringRedisMutexContendService(
            contender,
            Runnable::run,
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            redisTemplate,
            mockk<RedisMessageListenerContainer>(relaxed = true),
            scheduler
        )
    }

    private class ManualScheduledExecutor : ScheduledThreadPoolExecutor(1) {
        private val tasks = mutableListOf<ManualScheduledFuture<*>>()

        override fun <V : Any?> schedule(
            callable: Callable<V>,
            delay: Long,
            unit: TimeUnit
        ): ScheduledFuture<V> {
            return ManualScheduledFuture(callable).also { tasks += it }
        }

        fun run(index: Int) {
            tasks[index].run()
        }

        fun runActive() {
            tasks.toList().forEach {
                if (!it.isDone && !it.isCancelled) {
                    it.run()
                }
            }
        }

        val pendingTaskCount: Int
            get() = tasks.count { !it.isDone && !it.isCancelled }
    }

    private class ManualScheduledFuture<V>(callable: Callable<V>) :
        FutureTask<V>(callable),
        ScheduledFuture<V> {
        override fun getDelay(unit: TimeUnit): Long = 0
        override fun compareTo(other: Delayed): Int = 0
    }
}
