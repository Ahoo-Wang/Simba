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

package me.ahoo.simba.spring.boot.starter

import me.ahoo.simba.Simba
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.spi.LeaseBackend
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader

/**
 * The [Simba] client bean over the [LeaseBackend] bean that a backend auto-configuration (or the application)
 * defines, with every [SimbaEventListener] bean. [SimbaLifecycle] closes it on shutdown.
 */
@AutoConfiguration(
    afterName = [
        "me.ahoo.simba.spring.boot.starter.jdbc.SimbaJdbcAutoConfiguration",
        "me.ahoo.simba.spring.boot.starter.redis.SimbaRedisAutoConfiguration",
        "me.ahoo.simba.spring.boot.starter.zookeeper.SimbaZookeeperAutoConfiguration",
        "me.ahoo.simba.spring.boot.starter.metrics.SimbaMetricsAutoConfiguration",
    ]
)
@ConditionalOnSimbaEnabled
@EnableConfigurationProperties(SimbaProperties::class)
class SimbaAutoConfiguration {
    /**
     * Fails startup when the backend is ambiguous: several active backend modules and no `simba.backend`, or a
     * `simba.backend` that names an unavailable backend.
     */
    @Bean
    @Lazy(false) // validate even under spring.main.lazy-initialization=true
    fun simbaBackendSelection(environment: Environment, resourceLoader: ResourceLoader): SimbaBackendSelection {
        // The context class loader, as used by @ConditionalOnClass on the backend auto-configurations.
        val classLoader = resourceLoader.classLoader
        val active = SimbaBackend.entries.filter { it.isActive(environment, classLoader) }.map { it.id }
        val selected = SimbaBackend.selected(environment)
        if (selected == null) {
            check(active.size <= 1) {
                "Multiple Simba backends are active $active; set ${SimbaBackend.KEY} to one of them, " +
                    "or disable the others with simba.<backend>.enabled=false."
            }
            return SimbaBackendSelection(active.singleOrNull())
        }
        val backend = active.firstOrNull { it.equals(selected, ignoreCase = true) }
        checkNotNull(backend) {
            "${SimbaBackend.KEY}=$selected is not an active Simba backend; active backends: $active."
        }
        return SimbaBackendSelection(backend)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(LeaseBackend::class)
    @ConditionalOnMissingBean
    fun simba(
        backend: LeaseBackend,
        properties: SimbaProperties,
        listeners: ObjectProvider<SimbaEventListener>,
    ): Simba {
        val builder = Simba.builder().backend(backend).config(properties.session.toConfig())
        listeners.orderedStream().forEach { builder.listener(it) }
        return builder.build()
    }

    @Bean
    @ConditionalOnBean(Simba::class)
    @ConditionalOnMissingBean
    fun simbaLifecycle(simba: Simba): SimbaLifecycle = SimbaLifecycle(simba)
}

/** The backend the starter configures, `null` when no backend module is active. */
data class SimbaBackendSelection(val backend: String?)
