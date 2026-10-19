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

import me.ahoo.simba.Simba
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.boot.starter.Leadership
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.zookeeper.ZookeeperLeaseBackend
import me.ahoo.test.asserts.assert
import org.apache.curator.test.TestingServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimbaZookeeperAutoConfigurationTest {
    private val server = TestingServer()
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaZookeeperAutoConfiguration::class.java)
        )
        .withPropertyValues("simba.backend=zookeeper", "simba.zookeeper.connect-string=${server.connectString}")

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the client is elected on the zookeeper backend`() {
        contextRunner.run {
            assertThat(it).hasSingleBean(ZookeeperLeaseBackend::class.java)
            val leadership = Leadership()
            it.getBean(Simba::class.java).election("zookeeper-starter").start(leadership)
            (leadership.nextTerm().token > 0).assert().isTrue()
        }
    }

    @Test
    fun `selecting another backend leaves the zookeeper backend off`() {
        contextRunner.withPropertyValues("simba.backend=redis").run {
            assertThat(it).doesNotHaveBean(LeaseBackend::class.java).doesNotHaveBean(Simba::class.java)
        }
    }
}
