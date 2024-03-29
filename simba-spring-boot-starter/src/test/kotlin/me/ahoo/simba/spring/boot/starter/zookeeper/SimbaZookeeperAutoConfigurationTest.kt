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
package me.ahoo.simba.spring.boot.starter.zookeeper

import io.mockk.mockk
import me.ahoo.simba.core.MutexContendServiceFactory
import org.apache.curator.framework.CuratorFramework
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * SimbaJdbcAutoConfigurationTest .
 *
 * @author ahoo wang
 */
internal class SimbaZookeeperAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withBean(CuratorFramework::class.java, { mockk() })
            .withUserConfiguration(SimbaZookeeperAutoConfiguration::class.java)
            .run {
                assertThat(it)
                    .hasSingleBean(SimbaZookeeperAutoConfiguration::class.java)
                    .hasSingleBean(ZookeeperProperties::class.java)
                    .hasSingleBean(MutexContendServiceFactory::class.java)
            }
    }
}
