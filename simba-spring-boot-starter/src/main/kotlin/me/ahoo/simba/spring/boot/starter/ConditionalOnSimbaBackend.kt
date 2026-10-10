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
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.util.ClassUtils

/**
 * Matches when `simba.backend` is unset or names [value].
 *
 * @author ahoo wang
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnSimbaBackendCondition::class)
annotation class ConditionalOnSimbaBackend(val value: String)

/**
 * Backends the starter can configure, keyed by the `simba.backend` value.
 *
 * @author ahoo wang
 */
enum class SimbaBackend(val id: String, private val markerClass: String) {
    JDBC("jdbc", "me.ahoo.simba.jdbc.JdbcMutexContendServiceFactory"),
    REDIS("redis", "me.ahoo.simba.spring.redis.SpringRedisMutexContendServiceFactory"),
    ZOOKEEPER("zookeeper", "me.ahoo.simba.zookeeper.ZookeeperMutexContendServiceFactory");

    private val enabledKey: String = Simba.SIMBA_PREFIX + id + EnabledSuffix.KEY

    /**
     * Whether the backend module is on the classpath and not disabled through `simba.<backend>.enabled`.
     */
    fun isActive(environment: Environment, classLoader: ClassLoader?): Boolean {
        return ClassUtils.isPresent(markerClass, classLoader) &&
            environment.getProperty(enabledKey, Boolean::class.java, true)
    }

    companion object {
        const val KEY = Simba.SIMBA_PREFIX + "backend"

        fun selected(environment: Environment): String? {
            return environment.getProperty(KEY)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

internal class OnSimbaBackendCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val backend = metadata.getAnnotationAttributes(ConditionalOnSimbaBackend::class.java.name)!!["value"] as String
        val selected = SimbaBackend.selected(context.environment)
            ?: return ConditionOutcome.match("${SimbaBackend.KEY} is not set")
        return if (selected.equals(backend, ignoreCase = true)) {
            ConditionOutcome.match("${SimbaBackend.KEY}=$selected")
        } else {
            ConditionOutcome.noMatch("${SimbaBackend.KEY}=$selected selects another backend than $backend")
        }
    }
}
