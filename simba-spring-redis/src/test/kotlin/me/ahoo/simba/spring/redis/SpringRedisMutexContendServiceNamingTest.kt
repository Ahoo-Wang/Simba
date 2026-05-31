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

import io.mockk.mockk
import me.ahoo.simba.core.AbstractMutexContender
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService

class SpringRedisMutexContendServiceNamingTest {
    @Test
    fun `channels use same hash-tagged key that Lua scripts publish to`() {
        val contender = object : AbstractMutexContender("naming") {}
        val service = SpringRedisMutexContendService(
            contender = contender,
            handleExecutor = Runnable::run,
            ttl = Duration.ofSeconds(10),
            transition = Duration.ofSeconds(6),
            redisTemplate = mockk<StringRedisTemplate>(relaxed = true),
            listenerContainer = mockk<RedisMessageListenerContainer>(relaxed = true),
            scheduledExecutorService = mockk<ScheduledExecutorService>(relaxed = true)
        )

        assertThat(service.privateField<List<String>>("keys"), equalTo(listOf("{naming}")))
        assertThat(service.privateField<String>("mutexChannel"), equalTo("simba:{naming}"))
        assertThat(
            service.privateField<String>("contenderChannel"),
            equalTo("simba:{naming}:${contender.contenderId}")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> SpringRedisMutexContendService.privateField(name: String): T {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as T
    }
}
