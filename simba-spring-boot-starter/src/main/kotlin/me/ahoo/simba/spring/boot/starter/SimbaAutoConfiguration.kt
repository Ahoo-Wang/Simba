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

import me.ahoo.simba.core.ContendExecutors
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import java.util.concurrent.ExecutorService

/**
 * Simba Auto Configuration: infrastructure shared by all backends.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnSimbaEnabled
class SimbaAutoConfiguration {
    companion object {
        /**
         * Executor running `onAcquired` / `onReleased` callbacks. Define a bean with this name to replace it.
         */
        const val HANDLE_EXECUTOR_BEAN_NAME = "simbaHandleExecutor"
    }

    /**
     * Fails startup when the backend is ambiguous: several active backend modules and no `simba.backend`,
     * or a `simba.backend` that names an unavailable backend.
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

    /**
     * Dedicated daemon executor, so blocking callbacks cannot starve `ForkJoinPool.commonPool()`.
     */
    @Bean(name = [HANDLE_EXECUTOR_BEAN_NAME], destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = [HANDLE_EXECUTOR_BEAN_NAME])
    fun simbaHandleExecutor(): ExecutorService {
        return ContendExecutors.newCallbackExecutor("simba-callback")
    }
}

/**
 * The backend the starter configures, `null` when no backend module is active.
 *
 * @author ahoo wang
 */
data class SimbaBackendSelection(val backend: String?)
