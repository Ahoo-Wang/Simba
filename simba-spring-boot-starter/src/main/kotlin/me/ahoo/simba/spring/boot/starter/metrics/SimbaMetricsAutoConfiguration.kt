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
package me.ahoo.simba.spring.boot.starter.metrics

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.simba.spring.boot.starter.ConditionalOnSimbaEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Records Simba metrics when Micrometer is on the classpath and a [MeterRegistry] bean exists.
 *
 * @author ahoo wang
 */
@AutoConfiguration(
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"]
)
@ConditionalOnSimbaEnabled
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnProperty(
    value = [SimbaMetricsAutoConfiguration.ENABLED_KEY],
    matchIfMissing = true,
    havingValue = "true"
)
class SimbaMetricsAutoConfiguration {
    companion object {
        const val ENABLED_KEY = "simba.metrics.enabled"
    }

    @Bean
    @ConditionalOnMissingBean
    fun micrometerContendObserver(registry: MeterRegistry): MicrometerContendObserver {
        return MicrometerContendObserver(registry)
    }
}
