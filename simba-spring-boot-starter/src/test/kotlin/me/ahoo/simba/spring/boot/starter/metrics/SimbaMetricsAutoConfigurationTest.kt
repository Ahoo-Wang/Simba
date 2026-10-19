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
import me.ahoo.simba.Simba
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.boot.starter.Leadership
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.testing.InMemoryLeaseBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SimbaMetricsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaMetricsAutoConfiguration::class.java)
        )
        .withPropertyValues("simba.backend=zookeeper")
        .withBean(LeaseBackend::class.java, { InMemoryLeaseBackend() })

    @Test
    fun `the client reports to the meter registry`() {
        contextRunner.withBean(MeterRegistry::class.java, { SimpleMeterRegistry() }).run {
            assertThat(it).hasSingleBean(MicrometerSimbaEventListener::class.java)
            val leadership = Leadership()
            it.getBean(Simba::class.java).election("metered").start(leadership)
            leadership.nextTerm()
            // A snapshot is read on the engine loop, after the task that published the election.
            it.getBean(Simba::class.java).snapshot()
            it.getBean(MeterRegistry::class.java).get(MicrometerSimbaEventListener.LEADERSHIP)
                .tag("name", "metered").gauge().value().assert().isEqualTo(1.0)
        }
    }

    @Test
    fun `no meter registry or metrics disabled means no listener`() {
        contextRunner.run { assertThat(it).doesNotHaveBean(MicrometerSimbaEventListener::class.java) }
        contextRunner
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues("simba.metrics.enabled=false")
            .run {
                assertThat(it).doesNotHaveBean(MicrometerSimbaEventListener::class.java)
                it.getBean(Simba::class.java).assert().isNotNull()
            }
    }
}
