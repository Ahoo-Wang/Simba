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

package me.ahoo.simba.spring.boot.starter.health

import me.ahoo.simba.Simba
import me.ahoo.simba.spring.boot.starter.ConditionalOnSimbaEnabled
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean

/**
 * Registers the `simba` health indicator when Spring Boot health support is present
 * (`management.health.simba.enabled`, default `true`). It is not added to the liveness or readiness groups.
 */
@AutoConfiguration(after = [SimbaAutoConfiguration::class])
@ConditionalOnSimbaEnabled
@ConditionalOnClass(HealthIndicator::class)
@ConditionalOnBean(Simba::class)
@ConditionalOnEnabledHealthIndicator("simba")
class SimbaHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun simbaHealthIndicator(simba: Simba): SimbaHealthIndicator = SimbaHealthIndicator(simba)
}
