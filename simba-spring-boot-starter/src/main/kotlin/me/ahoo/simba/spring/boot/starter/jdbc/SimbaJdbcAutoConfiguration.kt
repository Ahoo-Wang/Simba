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
package me.ahoo.simba.spring.boot.starter.jdbc

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.jdbc.JdbcMutexContendServiceFactory
import me.ahoo.simba.jdbc.JdbcMutexOwnerRepository
import me.ahoo.simba.jdbc.MutexOwnerRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.util.concurrent.ForkJoinPool
import javax.sql.DataSource

/**
 * Simba Jdbc Auto Configuration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnSimbaJdbcEnabled
@ConditionalOnClass(
    JdbcMutexContendServiceFactory::class
)
@EnableConfigurationProperties(JdbcProperties::class)
class SimbaJdbcAutoConfiguration(private val jdbcProperties: JdbcProperties) {
    @Bean
    @ConditionalOnMissingBean
    fun mutexOwnerRepository(dataSource: DataSource): MutexOwnerRepository {
        return JdbcMutexOwnerRepository(dataSource)
    }

    @Bean
    @ConditionalOnMissingBean
    fun jdbcMutexContendServiceFactory(mutexOwnerRepository: MutexOwnerRepository): MutexContendServiceFactory {
        return JdbcMutexContendServiceFactory(
            mutexOwnerRepository = mutexOwnerRepository,
            handleExecutor = ForkJoinPool.commonPool(),
            initialDelay = jdbcProperties.initialDelay,
            ttl = jdbcProperties.ttl,
            transition = jdbcProperties.transition
        )
    }
}
