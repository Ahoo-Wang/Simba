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

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.event.SessionStatus
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.spi.LeaseName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Records [SimbaEvent]s as Micrometer meters (ADR 0010 §9):
 *
 * | Meter | Type | Tags |
 * |---|---|---|
 * | `simba.session.renew` | timer: successful keep-alive latency | |
 * | `simba.session.state` | gauge: 1 for the current session status, 0 otherwise | `state` |
 * | `simba.leadership` | gauge: 1 while this node holds a term of the name, 0 after losing it | `name` |
 * | `simba.revocations` | counter: ended terms, every reason | `reason` |
 * | `simba.work` | timer: leader work runs | `name`, `outcome` |
 *
 * Partition elections count as names too (`{group}.{partition}`). To keep the number of series bounded, a name's
 * `simba.leadership` gauge exists only from its first term until the term ends by a stop (`STOPPED`: the election
 * stopped, the client closed, or a lock was released), and revocations carry no name.
 */
class MicrometerSimbaEventListener(private val registry: MeterRegistry) : SimbaEventListener {
    companion object {
        const val SESSION_RENEW = "simba.session.renew"
        const val SESSION_STATE = "simba.session.state"
        const val LEADERSHIP = "simba.leadership"
        const val REVOCATIONS = "simba.revocations"
        const val WORK = "simba.work"
        private const val NAME = "name"
    }

    private val state = AtomicReference(SessionStatus.OPENING)
    private val leaders = ConcurrentHashMap<LeaseName, Leader>()

    private class Leader(val held: AtomicInteger, val gauge: Gauge)
    private val renew = Timer.builder(SESSION_RENEW)
        .description("Successful session keep-alive round trips")
        .register(registry)

    init {
        SessionStatus.entries.forEach { status ->
            Gauge.builder(SESSION_STATE, state) { if (it.get() == status) 1.0 else 0.0 }
                .description("1 for the current status of the Simba session")
                .tag("state", status.tag())
                .register(registry)
        }
    }

    override fun onEvent(event: SimbaEvent) {
        when (event) {
            is SimbaEvent.SessionOpened -> state.set(SessionStatus.LIVE)
            is SimbaEvent.SessionRenewed -> {
                state.set(SessionStatus.LIVE)
                renew.record(event.latency)
            }

            is SimbaEvent.SessionSuspended -> state.set(SessionStatus.SUSPENDED)
            is SimbaEvent.SessionExpired -> state.set(SessionStatus.OPENING)
            is SimbaEvent.SessionClosed -> state.set(SessionStatus.CLOSED)
            is SimbaEvent.Elected -> leader(event.name).held.set(1)
            is SimbaEvent.Revoked -> {
                if (event.reason == RevokeReason.STOPPED) {
                    leaders.remove(event.name)?.let { registry.remove(it.gauge) }
                } else {
                    leaders[event.name]?.held?.set(0)
                }
                Counter.builder(REVOCATIONS)
                    .description("Terms this node lost or gave up")
                    .tag("reason", event.reason.tag())
                    .register(registry)
                    .increment()
            }

            is SimbaEvent.WorkCompleted -> Timer.builder(WORK)
                .description("Leader work runs")
                .tags(NAME, event.name.value, "outcome", event.outcome.tag())
                .register(registry)
                .record(event.duration)

            is SimbaEvent.AcquireFailed -> Unit
        }
    }

    private fun leader(name: LeaseName): Leader =
        leaders.computeIfAbsent(name) { key ->
            val held = AtomicInteger()
            val gauge = Gauge.builder(LEADERSHIP, held) { it.get().toDouble() }
                .description("1 while this node holds a term of the name")
                .tag(NAME, key.value)
                .register(registry)
            Leader(held, gauge)
        }

    private fun Enum<*>.tag(): String = name.lowercase()
}
