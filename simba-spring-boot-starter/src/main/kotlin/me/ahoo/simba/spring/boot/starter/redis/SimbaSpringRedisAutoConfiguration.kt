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
package me.ahoo.simba.spring.boot.starter.redis

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.spring.redis.SpringRedisMutexContendServiceFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * Simba Spring Redis AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [DataRedisAutoConfiguration::class])
@ConditionalOnSimbaRedisEnabled
@ConditionalOnClass(
    StringRedisTemplate::class
)
@EnableConfigurationProperties(
    RedisProperties::class
)
class SimbaSpringRedisAutoConfiguration(private val redisProperties: RedisProperties) {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(
        RedisConnectionFactory::class
    )
    fun simbaRedisMessageListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        return container
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate::class)
    fun redisMutexContendServiceFactory(
        redisTemplate: StringRedisTemplate,
        listenerContainer: RedisMessageListenerContainer
    ): MutexContendServiceFactory {
        return SpringRedisMutexContendServiceFactory(
            redisProperties.ttl,
            redisProperties.transition,
            redisTemplate,
            listenerContainer
        )
    }
}
