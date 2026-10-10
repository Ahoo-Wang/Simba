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
package me.ahoo.simba.spring.boot.starter.endpoint

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexOwner
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimbaEndpointAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SimbaAutoConfiguration::class.java,
                SimbaZookeeperAutoConfiguration::class.java,
                SimbaEndpointAutoConfiguration::class.java
            )
        )
        .withPropertyValues("simba.backend=zookeeper")

    @Test
    fun `endpoint is absent unless exposed`() {
        contextRunner.run {
            assertThat(it).hasNotFailed()
                .doesNotHaveBean(SimbaEndpoint::class.java)
                .doesNotHaveBean(SimbaServiceTracker::class.java)
        }
    }

    @Test
    fun `endpoint lists running services and their owner`() {
        TestingServer().use { server ->
            CuratorFrameworkFactory.newClient(server.connectString, RetryNTimes(1, 10)).use { curator ->
                curator.start()
                contextRunner
                    .withBean(CuratorFramework::class.java, { curator })
                    .withPropertyValues("management.endpoints.web.exposure.include=simba")
                    .run {
                        val endpoint = it.getBean(SimbaEndpoint::class.java)
                        val acquired = CountDownLatch(1)
                        val contendService = it.getBean(MutexContendServiceFactory::class.java)
                            .createMutexContendService(object : AbstractMutexContender("endpoint") {
                                override fun onAcquired(mutexState: MutexState) {
                                    acquired.countDown()
                                }
                            })
                        endpoint.mutexes().mutexes.assert().isEmpty()

                        contendService.start()
                        acquired.await(30, TimeUnit.SECONDS).assert().isTrue()

                        val entry = endpoint.mutexes().mutexes.single()
                        entry.mutex.assert().isEqualTo("endpoint")
                        entry.contenderId.assert().isEqualTo(contendService.contenderId)
                        entry.status.assert().isEqualTo("RUNNING")
                        entry.owner.assert().isTrue()
                        val owner = checkNotNull(entry.currentOwner)
                        owner.ownerId.assert().isEqualTo(contendService.contenderId)
                        owner.fencingToken.assert().isGreaterThan(MutexOwner.NO_FENCING_TOKEN)
                        owner.ttlAt.assert().isNull()
                        endpoint.mutex("endpoint")!!.mutexes.assert().containsExactly(entry)
                        endpoint.mutex("unknown").assert().isNull()

                        contendService.stop()
                        endpoint.mutexes().mutexes.assert().isEmpty()
                    }
            }
        }
    }

    @Test
    fun `bounded leases expose their times`() {
        val owner = MutexOwner("c1", acquiredAt = 1_000, ttlAt = 11_000, transitionAt = 17_000, fencingToken = 3)

        SimbaEndpoint.OwnerDescriptor.of(owner).assert().isEqualTo(
            SimbaEndpoint.OwnerDescriptor(
                ownerId = "c1",
                fencingToken = 3,
                acquiredAt = Instant.ofEpochMilli(1_000),
                ttlAt = Instant.ofEpochMilli(11_000),
                transitionAt = Instant.ofEpochMilli(17_000)
            )
        )
    }

    @Test
    fun `descriptors serialize as actuator JSON`() {
        val descriptor = SimbaEndpoint.MutexesDescriptor(
            listOf(
                SimbaEndpoint.MutexDescriptor(
                    mutex = "report",
                    contenderId = "0:42@host",
                    status = "RUNNING",
                    owner = true,
                    currentOwner = SimbaEndpoint.OwnerDescriptor(
                        ownerId = "0:42@host",
                        fencingToken = 7,
                        acquiredAt = Instant.ofEpochMilli(1_000),
                        ttlAt = null,
                        transitionAt = null
                    )
                )
            )
        )

        val mapper = JsonMapper.builder().build()
        mapper.readTree(mapper.writeValueAsString(descriptor)).assert().isEqualTo(
            mapper.readTree(
                """{"mutexes":[{"mutex":"report","contenderId":"0:42@host","status":"RUNNING","owner":true,""" +
                    """"currentOwner":{"ownerId":"0:42@host","fencingToken":7,""" +
                    """"acquiredAt":"1970-01-01T00:00:01Z","ttlAt":null,"transitionAt":null}}]}"""
            )
        )
    }
}
