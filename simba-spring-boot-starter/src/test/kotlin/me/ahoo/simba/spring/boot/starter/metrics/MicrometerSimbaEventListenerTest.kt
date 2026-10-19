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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class MicrometerSimbaEventListenerTest {
    private val registry = SimpleMeterRegistry()
    private val listener = MicrometerSimbaEventListener(registry)
    private val session = SessionId.random()
    private val name = LeaseName("orders")

    private fun state(state: String): Double =
        registry.get(MicrometerSimbaEventListener.SESSION_STATE).tag("state", state).gauge().value()

    @Test
    fun `session state follows the session events`() {
        state("opening").assert().isEqualTo(1.0)
        listener.onEvent(SimbaEvent.SessionOpened(session))
        state("live").assert().isEqualTo(1.0)
        state("opening").assert().isEqualTo(0.0)
        listener.onEvent(SimbaEvent.SessionSuspended(session, IllegalStateException("down")))
        state("suspended").assert().isEqualTo(1.0)
        listener.onEvent(SimbaEvent.SessionExpired(session, RevokeReason.DEADLINE_PASSED))
        state("opening").assert().isEqualTo(1.0)
        listener.onEvent(SimbaEvent.SessionRenewed(session, Duration.ofMillis(3)))
        state("live").assert().isEqualTo(1.0)
        registry.get(MicrometerSimbaEventListener.SESSION_RENEW).timer().count().assert().isEqualTo(1L)
        listener.onEvent(SimbaEvent.SessionClosed(session))
        state("closed").assert().isEqualTo(1.0)
        state("live").assert().isEqualTo(0.0)
    }

    @Test
    fun `leadership and revocations are tagged by name`() {
        listener.onEvent(SimbaEvent.Elected(name, 1))
        registry.get(MicrometerSimbaEventListener.LEADERSHIP).tag("name", "orders").gauge().value()
            .assert().isEqualTo(1.0)
        listener.onEvent(SimbaEvent.Revoked(name, 1, RevokeReason.TAKEN_OVER))
        registry.get(MicrometerSimbaEventListener.LEADERSHIP).tag("name", "orders").gauge().value()
            .assert().isEqualTo(0.0)
        registry.get(MicrometerSimbaEventListener.REVOCATIONS).tags("reason", "taken_over", "kind", "election")
            .counter().count().assert().isEqualTo(1.0)
        registry.get(MicrometerSimbaEventListener.REVOCATIONS).counter().id.getTag("name").assert().isNull()
        listener.onEvent(SimbaEvent.AcquireFailed(name, IllegalStateException("down")))
    }

    @Test
    fun `a stopped term removes the name's gauge`() {
        listener.onEvent(SimbaEvent.Elected(name, 1))
        listener.onEvent(SimbaEvent.Revoked(name, 1, RevokeReason.STOPPED))
        registry.find(MicrometerSimbaEventListener.LEADERSHIP).tag("name", "orders").gauge().assert().isNull()
        registry.get(MicrometerSimbaEventListener.REVOCATIONS).tag("reason", "stopped")
            .counter().count().assert().isEqualTo(1.0)

        listener.onEvent(SimbaEvent.Elected(name, 2))
        registry.get(MicrometerSimbaEventListener.LEADERSHIP).tag("name", "orders").gauge().value()
            .assert().isEqualTo(1.0)
    }

    @Test
    fun `a stop of a name never elected is only counted`() {
        listener.onEvent(SimbaEvent.Revoked(name, 1, RevokeReason.STOPPED))
        registry.find(MicrometerSimbaEventListener.LEADERSHIP).gauges().assert().isEmpty()
    }

    @Test
    fun `locks are counted together, never by name`() {
        val invoice = LeaseName("invoice-42")
        listener.onEvent(SimbaEvent.Elected(invoice, 1, ElectionKind.LOCK))
        listener.onEvent(SimbaEvent.Elected(LeaseName("invoice-43"), 1, ElectionKind.LOCK))
        registry.get(MicrometerSimbaEventListener.LOCKS_HELD).gauge().value().assert().isEqualTo(2.0)
        registry.find(MicrometerSimbaEventListener.LEADERSHIP).gauges().assert().isEmpty()

        listener.onEvent(SimbaEvent.Revoked(invoice, 1, RevokeReason.STOPPED, ElectionKind.LOCK))
        listener.onEvent(SimbaEvent.Revoked(invoice, 1, RevokeReason.STOPPED, ElectionKind.LOCK))
        registry.get(MicrometerSimbaEventListener.LOCKS_HELD).gauge().value().assert().isEqualTo(0.0)
        registry.get(MicrometerSimbaEventListener.REVOCATIONS).tags("reason", "stopped", "kind", "lock")
            .counter().count().assert().isEqualTo(2.0)
        registry.find(MicrometerSimbaEventListener.REVOCATIONS).counters().forEach {
            it.id.getTag("name").assert().isNull()
        }
    }

    @Test
    fun `partitions are tagged by their lease name and removed when they move away`() {
        val partition = LeaseName("billing.3")
        listener.onEvent(SimbaEvent.Elected(partition, 7, ElectionKind.GROUP))
        registry.get(MicrometerSimbaEventListener.LEADERSHIP).tag("name", "billing.3").gauge().value()
            .assert().isEqualTo(1.0)
        listener.onEvent(SimbaEvent.Revoked(partition, 7, RevokeReason.STOPPED, ElectionKind.GROUP))
        registry.find(MicrometerSimbaEventListener.LEADERSHIP).gauges().assert().isEmpty()
        registry.get(MicrometerSimbaEventListener.REVOCATIONS).tags("reason", "stopped", "kind", "group")
            .counter().count().assert().isEqualTo(1.0)
    }

    @Test
    fun `work runs are timed by outcome`() {
        listener.onEvent(SimbaEvent.WorkCompleted(name, 1, WorkOutcome.FAILED, Duration.ofMillis(5)))
        registry.get(MicrometerSimbaEventListener.WORK).tags("name", "orders", "outcome", "failed")
            .timer().count().assert().isEqualTo(1L)
    }
}
