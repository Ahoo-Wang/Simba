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

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.test.MutexContendServiceSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

/**
 * RedisMutexContendServiceTest .
 *
 * @author ahoo wang
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SpringRedisMutexContendServiceTest : MutexContendServiceSpec() {
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    override lateinit var mutexContendServiceFactory: MutexContendServiceFactory
    lateinit var listenerContainer: RedisMessageListenerContainer

    @BeforeAll
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        val stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        listenerContainer = RedisMessageListenerContainer()
        listenerContainer.setConnectionFactory(lettuceConnectionFactory)
        listenerContainer.afterPropertiesSet()
        listenerContainer.start()
        mutexContendServiceFactory = SpringRedisMutexContendServiceFactory(
            ttl = Duration.ofSeconds(2),
            transition = Duration.ofSeconds(1),
            redisTemplate = stringRedisTemplate,
            listenerContainer = listenerContainer,
            handleExecutor = ForkJoinPool.commonPool(),
            scheduledExecutorService = Executors.newScheduledThreadPool(1)
        )
    }

    @AfterAll
    fun destroy() {
        if (this::lettuceConnectionFactory.isInitialized) {
            lettuceConnectionFactory.destroy()
        }
        if (this::listenerContainer.isInitialized) {
            listenerContainer.stop()
        }
    }

}
