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
package me.ahoo.simba.spring.boot.starter.endpoint

import me.ahoo.simba.spring.boot.starter.ConditionalOnSimbaEnabled
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Registers the `simba` Actuator endpoint when it is available (enabled and exposed), together with the tracker the
 * backend factories report to; nothing is tracked otherwise.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnSimbaEnabled
@ConditionalOnClass(Endpoint::class)
@ConditionalOnAvailableEndpoint(SimbaEndpoint::class)
class SimbaEndpointAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun simbaServiceTracker(): SimbaServiceTracker {
        return SimbaServiceTracker()
    }

    @Bean
    @ConditionalOnMissingBean
    fun simbaEndpoint(tracker: SimbaServiceTracker): SimbaEndpoint {
        return SimbaEndpoint(tracker)
    }
}
