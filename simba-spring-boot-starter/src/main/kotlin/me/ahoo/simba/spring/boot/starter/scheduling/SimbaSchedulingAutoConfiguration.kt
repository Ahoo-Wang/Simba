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
package me.ahoo.simba.spring.boot.starter.scheduling

import me.ahoo.simba.spring.boot.starter.ConditionalOnSimbaEnabled
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Runs [SimbaScheduled] methods and `SimbaScheduler` beans with the application context.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnSimbaEnabled
@ConditionalOnProperty(
    value = [SimbaSchedulingAutoConfiguration.ENABLED_KEY],
    matchIfMissing = true,
    havingValue = "true"
)
// Spring instantiates configuration classes; the post-processor bean must be static.
@Suppress("UtilityClassWithPublicConstructor")
class SimbaSchedulingAutoConfiguration {
    companion object {
        const val ENABLED_KEY = "simba.scheduling.enabled"

        @Bean
        @JvmStatic
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun simbaScheduledBeanPostProcessor(): SimbaScheduledBeanPostProcessor {
            return SimbaScheduledBeanPostProcessor()
        }
    }
}
