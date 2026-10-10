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

import io.mockk.mockk
import me.ahoo.simba.spring.boot.starter.jdbc.SimbaJdbcAutoConfiguration
import me.ahoo.simba.spring.boot.starter.redis.SimbaSpringRedisAutoConfiguration
import me.ahoo.simba.spring.redis.SpringRedisMutexContendServiceFactory
import me.ahoo.simba.zookeeper.ZookeeperMutexContendServiceFactory
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import javax.sql.DataSource

/**
 * The test classpath carries all three backend modules.
 */
internal class SimbaBackendSelectionTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SimbaAutoConfiguration::class.java))

    @Test
    fun `several active backends without simba backend fail fast`() {
        contextRunner.run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).rootCause().hasMessageContaining("Multiple Simba backends are active")
        }
    }

    @Test
    fun `ambiguity fails fast even with global lazy initialization`() {
        contextRunner
            .withInitializer { context ->
                (context as org.springframework.context.support.GenericApplicationContext)
                    .addBeanFactoryPostProcessor(org.springframework.boot.LazyInitializationBeanFactoryPostProcessor())
            }
            .run {
                assertThat(it).hasFailed()
            }
    }

    @Test
    fun `simba backend selects one of several`() {
        contextRunner.withPropertyValues("simba.backend=Redis").run {
            assertThat(it).hasNotFailed()
            assertThat(it.getBean(SimbaBackendSelection::class.java).backend).isEqualTo("redis")
        }
    }

    @Test
    fun `disabling the other backends leaves one`() {
        contextRunner.withPropertyValues("simba.jdbc.enabled=false", "simba.zookeeper.enabled=false").run {
            assertThat(it.getBean(SimbaBackendSelection::class.java).backend).isEqualTo("redis")
        }
    }

    @Test
    fun `a single backend module needs no selection`() {
        contextRunner
            .withClassLoader(
                FilteredClassLoader(
                    SpringRedisMutexContendServiceFactory::class.java,
                    ZookeeperMutexContendServiceFactory::class.java
                )
            )
            .run {
                assertThat(it.getBean(SimbaBackendSelection::class.java).backend).isEqualTo("jdbc")
            }
    }

    @Test
    fun `selecting an inactive backend fails fast`() {
        contextRunner.withPropertyValues("simba.backend=jdbc", "simba.jdbc.enabled=false").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).rootCause().hasMessageContaining("is not an active Simba backend")
        }
    }

    @Test
    fun `unselected backend auto-configuration stays off`() {
        contextRunner
            .withPropertyValues("simba.backend=redis")
            .withConfiguration(AutoConfigurations.of(SimbaJdbcAutoConfiguration::class.java))
            .withBean(DataSource::class.java, { mockk() })
            .run {
                assertThat(it).doesNotHaveBean(SimbaJdbcAutoConfiguration::class.java)
            }
    }

    @Test
    fun `redis auto-configuration requires the simba redis module, not only spring data redis`() {
        contextRunner
            .withPropertyValues("simba.backend=jdbc")
            .withClassLoader(FilteredClassLoader(SpringRedisMutexContendServiceFactory::class.java))
            .withConfiguration(
                AutoConfigurations.of(
                    DataRedisAutoConfiguration::class.java,
                    SimbaSpringRedisAutoConfiguration::class.java
                )
            )
            .run {
                assertThat(it).doesNotHaveBean(SimbaSpringRedisAutoConfiguration::class.java)
            }
    }
}
