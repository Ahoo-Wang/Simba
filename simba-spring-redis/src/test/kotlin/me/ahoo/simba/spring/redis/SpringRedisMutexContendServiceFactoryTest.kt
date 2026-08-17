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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.Executors

class SpringRedisMutexContendServiceFactoryTest {
    @Test
    fun `factory rejects invalid lease durations`() {
        assertThrows<IllegalArgumentException> { newFactory(Duration.ZERO, Duration.ZERO) }
        assertThrows<IllegalArgumentException> { newFactory(Duration.ofNanos(1), Duration.ZERO) }
        assertThrows<IllegalArgumentException> { newFactory(Duration.ofMillis(1), Duration.ofMillis(-1)) }
        assertThrows<IllegalArgumentException> {
            newFactory(Duration.ofMillis(Long.MAX_VALUE), Duration.ofMillis(1))
        }
    }

    @Test
    fun `close shuts down the scheduled executor service`() {
        val scheduledExecutorService = Executors.newScheduledThreadPool(1)
        val factory = SpringRedisMutexContendServiceFactory(
            ttl = Duration.ofSeconds(10),
            transition = Duration.ofSeconds(6),
            redisTemplate = org.springframework.data.redis.core.StringRedisTemplate(),
            listenerContainer = RedisMessageListenerContainer(),
            scheduledExecutorService = scheduledExecutorService
        )

        factory.close()

        assertThat(scheduledExecutorService.isShutdown, equalTo(true))
    }

    private fun newFactory(ttl: Duration, transition: Duration): SpringRedisMutexContendServiceFactory {
        return SpringRedisMutexContendServiceFactory(
            ttl = ttl,
            transition = transition,
            redisTemplate = StringRedisTemplate(),
            listenerContainer = RedisMessageListenerContainer(),
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor().also { it.shutdown() }
        )
    }
}
