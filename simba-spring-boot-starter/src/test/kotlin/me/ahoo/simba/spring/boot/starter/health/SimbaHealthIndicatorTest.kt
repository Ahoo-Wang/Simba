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

import me.ahoo.simba.Simba
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.ElectionSnapshot
import me.ahoo.simba.event.ElectionStatus
import me.ahoo.simba.event.SessionSnapshot
import me.ahoo.simba.event.SessionStatus
import me.ahoo.simba.event.SimbaSnapshot
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.testing.InMemoryLeaseBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class SimbaHealthIndicatorTest {
    private val leaseDuration = Duration.ofSeconds(15)
    private val session = SessionId.random()

    private fun snapshot(status: SessionStatus, notLiveFor: Duration) =
        SimbaSnapshot(
            SessionSnapshot(session, status, Duration.ofSeconds(10), notLiveFor),
            listOf(
                ElectionSnapshot(LeaseName("orders"), ElectionStatus.LEADER, 3, Duration.ofSeconds(10)),
                ElectionSnapshot(
                    LeaseName("invoice-42"),
                    ElectionStatus.LEADER,
                    5,
                    Duration.ofSeconds(10),
                    ElectionKind.LOCK,
                ),
            ),
            leaseDuration,
        )

    @Test
    fun `a live session is up`() {
        val health = SimbaHealthIndicator { snapshot(SessionStatus.LIVE, Duration.ZERO) }.health(true)!!
        health.status.assert().isEqualTo(Status.UP)
        health.details["status"].assert().isEqualTo("LIVE")
        health.details["session"].assert().isEqualTo(session.value)
        health.details["leading"].assert().isEqualTo(listOf("orders"))
        health.details["locksHeld"].assert().isEqualTo(1)
    }

    @Test
    fun `a session suspended within the lease duration is still up`() {
        SimbaHealthIndicator { snapshot(SessionStatus.SUSPENDED, leaseDuration) }.health(false)!!.status
            .assert().isEqualTo(Status.UP)
    }

    @Test
    fun `a session not live for longer than the lease duration is down`() {
        SimbaHealthIndicator { snapshot(SessionStatus.OPENING, leaseDuration.plusMillis(1)) }.health(false)!!.status
            .assert().isEqualTo(Status.DOWN)
    }

    @Test
    fun `the indicator is registered for the client`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaHealthAutoConfiguration::class.java)
            )
            .withPropertyValues("simba.backend=zookeeper")
            .withBean(LeaseBackend::class.java, { InMemoryLeaseBackend() })
            .run {
                assertThat(it).hasSingleBean(SimbaHealthIndicator::class.java)
                it.getBean(Simba::class.java).assert().isNotNull()
                it.getBean(SimbaHealthIndicator::class.java).health(true)!!.status.assert().isNotNull()
            }
    }
}
