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
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.spring.boot.starter.zookeeper.SimbaZookeeperAutoConfiguration
import org.apache.curator.framework.CuratorFramework
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool

internal class SimbaAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SimbaAutoConfiguration::class.java))
        .withPropertyValues("simba.backend=zookeeper")

    @Test
    fun `provides a dedicated callback executor`() {
        contextRunner.run {
            val executor = it.getBean(SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME, Executor::class.java)
            assertThat(executor).isInstanceOf(ExecutorService::class.java)
            assertThat(executor).isNotSameAs(ForkJoinPool.commonPool())
        }
    }

    @Test
    fun `shuts the default executor down with the context`() {
        var executor: ExecutorService? = null
        contextRunner.run {
            executor = it.getBean(SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME, ExecutorService::class.java)
        }
        assertThat(executor!!.isShutdown).isTrue()
    }

    @Test
    fun `user defined executor replaces the default and reaches backends`() {
        val userExecutor = Executor { it.run() }
        contextRunner
            .withConfiguration(AutoConfigurations.of(SimbaZookeeperAutoConfiguration::class.java))
            .withBean(SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME, Executor::class.java, { userExecutor })
            .withBean(CuratorFramework::class.java, { mockk() })
            .run {
                assertThat(it.getBean(SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME)).isSameAs(userExecutor)
                assertThat(it).hasSingleBean(MutexContendServiceFactory::class.java)
            }
    }

    @Test
    fun `disabled simba provides no executor`() {
        contextRunner
            .withPropertyValues("simba.enabled=false")
            .run {
                assertThat(it).doesNotHaveBean(SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME)
            }
    }
}
