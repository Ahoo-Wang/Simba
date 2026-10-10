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
package me.ahoo.simba.spring.boot.starter.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.ContendObserver
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
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaMetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SimbaAutoConfiguration::class.java,
                SimbaZookeeperAutoConfiguration::class.java,
                SimbaMetricsAutoConfiguration::class.java
            )
        )
        .withPropertyValues("simba.backend=zookeeper")

    @Test
    fun `backend factory records metrics and forwards to custom observers`() {
        TestingServer().use { server ->
            CuratorFrameworkFactory.newClient(server.connectString, RetryNTimes(1, 10)).use { curator ->
                curator.start()
                val custom = LinkedBlockingQueue<String>()
                contextRunner
                    .withBean(CuratorFramework::class.java, { curator })
                    .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                    .withBean(ContendObserver::class.java, {
                        object : ContendObserver {
                            override fun onAcquired(mutex: String) {
                                custom.add("acquired:$mutex")
                            }
                        }
                    })
                    .run {
                        val registry = it.getBean(MeterRegistry::class.java)
                        val acquired = CountDownLatch(1)
                        val contendService = it.getBean(MutexContendServiceFactory::class.java)
                            .createMutexContendService(object : AbstractMutexContender("metrics") {
                                override fun onAcquired(mutexState: MutexState) {
                                    acquired.countDown()
                                }
                            })
                        contendService.start()
                        acquired.await(30, TimeUnit.SECONDS).assert().isTrue()

                        registry.get(MicrometerContendObserver.OWNER).tag("mutex", "metrics").gauge().value()
                            .assert().isEqualTo(1.0)
                        custom.poll(2, TimeUnit.SECONDS).assert().isEqualTo("acquired:metrics")

                        contendService.stop()
                        registry.get(MicrometerContendObserver.OWNER).tag("mutex", "metrics").gauge().value()
                            .assert().isEqualTo(0.0)
                    }
            }
        }
    }

    @Test
    fun `binds to the registry of Spring Boot metrics auto-configuration`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SimbaMetricsAutoConfiguration::class.java,
                    SimbaAutoConfiguration::class.java,
                    SimpleMetricsExportAutoConfiguration::class.java,
                    CompositeMeterRegistryAutoConfiguration::class.java,
                    MetricsAutoConfiguration::class.java
                )
            )
            .withPropertyValues("simba.backend=zookeeper")
            .run {
                assertThat(it).hasSingleBean(MicrometerContendObserver::class.java)
                it.getBean(MicrometerContendObserver::class.java).onLeaseExpired("boot")
                it.getBean(MeterRegistry::class.java).get(MicrometerContendObserver.LEASE_EXPIRED)
                    .tag("mutex", "boot").counter().count().assert().isEqualTo(1.0)
            }
    }

    @Test
    fun `no observer without a meter registry`() {
        contextRunner.run {
            assertThat(it).hasNotFailed().doesNotHaveBean(MicrometerContendObserver::class.java)
        }
    }

    @Test
    fun `metrics can be disabled`() {
        contextRunner
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues("simba.metrics.enabled=false")
            .run {
                assertThat(it).doesNotHaveBean(MicrometerContendObserver::class.java)
            }
    }
}
