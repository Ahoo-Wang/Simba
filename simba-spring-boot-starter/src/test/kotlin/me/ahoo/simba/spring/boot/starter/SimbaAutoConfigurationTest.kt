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
import me.ahoo.simba.event.SessionStatus
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.testing.InMemoryLeaseBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaAutoConfigurationTest {
    // All backend modules are on the test classpath; none of their auto-configurations is loaded here.
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SimbaAutoConfiguration::class.java))
        .withPropertyValues("simba.backend=zookeeper")

    private val withBackend = contextRunner.withBean(LeaseBackend::class.java, { InMemoryLeaseBackend() })

    @Test
    fun `a lease backend bean gets a simba client with the session properties`() {
        withBackend
            .withPropertyValues("simba.session.lease-duration=6s", "simba.session.margin=10ms")
            .run {
                assertThat(it).hasSingleBean(Simba::class.java).hasSingleBean(SimbaLifecycle::class.java)
                val config = it.getBean(Simba::class.java).config
                config.leaseDuration.assert().isEqualTo(Duration.ofSeconds(6))
                config.renewInterval.assert().isEqualTo(Duration.ofSeconds(2))
                config.margin.assert().isEqualTo(Duration.ofMillis(10))
            }
    }

    @Test
    fun `an explicit renew interval is kept`() {
        withBackend.withPropertyValues("simba.session.renew-interval=1s").run {
            it.getBean(Simba::class.java).config.renewInterval.assert().isEqualTo(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `invalid session timing fails startup`() {
        withBackend.withPropertyValues("simba.session.lease-duration=4s", "simba.session.renew-interval=3s").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).rootCause().hasMessageContaining("renewInterval")
        }
    }

    @Test
    fun `no lease backend means no client`() {
        contextRunner.run {
            assertThat(it).hasNotFailed().doesNotHaveBean(Simba::class.java)
        }
    }

    @Test
    fun `simba disabled configures nothing`() {
        withBackend.withPropertyValues("simba.enabled=false").run {
            assertThat(it).doesNotHaveBean(Simba::class.java).doesNotHaveBean(SimbaBackendSelection::class.java)
        }
    }

    @Test
    fun `event listener beans receive the client's events`() {
        val events = LinkedBlockingQueue<SimbaEvent>()
        withBackend.withBean(SimbaEventListener::class.java, { SimbaEventListener { events += it } }).run {
            assertThat(it).hasSingleBean(Simba::class.java)
            val opened = checkNotNull(events.poll(Leadership.WAIT_SECONDS, TimeUnit.SECONDS))
            (opened is SimbaEvent.SessionOpened).assert().isTrue()
        }
    }

    @Test
    fun `stopping the lifecycle closes the client and its session`() {
        withBackend.run {
            val simba = it.getBean(Simba::class.java)
            val lifecycle = it.getBean(SimbaLifecycle::class.java)
            lifecycle.isRunning.assert().isTrue()
            lifecycle.phase.assert().isEqualTo(SimbaLifecycle.PHASE)

            lifecycle.stop()

            lifecycle.isRunning.assert().isFalse()
            simba.snapshot().session.status.assert().isEqualTo(SessionStatus.CLOSED)
        }
    }
}
