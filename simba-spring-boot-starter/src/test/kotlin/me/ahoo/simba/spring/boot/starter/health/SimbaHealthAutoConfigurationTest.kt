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

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.spring.boot.starter.zookeeper.SimbaZookeeperAutoConfiguration
import me.ahoo.test.asserts.assert
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimbaHealthAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SimbaAutoConfiguration::class.java,
                SimbaZookeeperAutoConfiguration::class.java,
                SimbaHealthAutoConfiguration::class.java
            )
        )
        .withPropertyValues("simba.backend=zookeeper")

    @Test
    fun `health indicator can be disabled`() {
        contextRunner.withPropertyValues("management.health.simba.enabled=false").run {
            assertThat(it).hasNotFailed().doesNotHaveBean(SimbaHealthIndicator::class.java)
        }
    }

    @Test
    fun `health indicator observes the backend factory`() {
        TestingServer().use { server ->
            CuratorFrameworkFactory.newClient(server.connectString, RetryNTimes(1, 10)).use { curator ->
                curator.start()
                contextRunner
                    .withBean(CuratorFramework::class.java, { curator })
                    .run {
                        val indicator = it.getBean(SimbaHealthIndicator::class.java)
                        val acquired = CountDownLatch(1)
                        val contendService = it.getBean(MutexContendServiceFactory::class.java)
                            .createMutexContendService(object : AbstractMutexContender("health") {
                                override fun onAcquired(mutexState: MutexState) {
                                    acquired.countDown()
                                }
                            })
                        contendService.start()
                        acquired.await(30, TimeUnit.SECONDS).assert().isTrue()

                        val health = indicator.health()
                        health.status.assert().isEqualTo(Status.UP)
                        health.details["mutexes"].toString().assert().contains("health")

                        contendService.stop()
                    }
            }
        }
    }
}
