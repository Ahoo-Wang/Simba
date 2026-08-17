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
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.Executors

class SpringRedisMutexContendServiceFactoryTest {
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
}
