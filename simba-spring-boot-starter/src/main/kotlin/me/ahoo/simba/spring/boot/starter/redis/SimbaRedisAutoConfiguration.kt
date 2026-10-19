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

import me.ahoo.simba.redis.RedisLeaseBackend
import me.ahoo.simba.redis.ReplicaWait
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.redis.SpringRedisScripting
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.RedisConnectionFactory

/** A [RedisLeaseBackend] over the single `RedisConnectionFactory`, through [SpringRedisScripting]. */
@AutoConfiguration(afterName = ["org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"])
@ConditionalOnSimbaRedisEnabled
@ConditionalOnClass(SpringRedisScripting::class, RedisConnectionFactory::class)
@EnableConfigurationProperties(RedisProperties::class)
class SimbaRedisAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LeaseBackend::class)
    @ConditionalOnSingleCandidate(RedisConnectionFactory::class)
    fun redisLeaseBackend(connectionFactory: RedisConnectionFactory, properties: RedisProperties): RedisLeaseBackend {
        val scripting = SpringRedisScripting(connectionFactory)
        val wait = properties.waitReplicas.takeIf { it > 0 }?.let { ReplicaWait(it, properties.waitTimeout) }
        return properties.routeBySlot?.let { RedisLeaseBackend(scripting, wait, it) }
            ?: RedisLeaseBackend(scripting, wait)
    }
}
