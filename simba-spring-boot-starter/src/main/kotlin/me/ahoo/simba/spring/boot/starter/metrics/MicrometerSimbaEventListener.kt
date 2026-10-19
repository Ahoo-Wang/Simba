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
import me.ahoo.simba.event.ElectionKind
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
 * | `simba.leadership` | gauge: 1 while this node holds a term of the election, 0 after losing it | `name` |
 * | `simba.locks.held` | gauge: locks this node holds | |
 * | `simba.revocations` | counter: ended terms, every reason | `reason`, `kind` (`election`, `lock`, `group`) |
 * | `simba.work` | timer: leader work runs | `name`, `outcome` |
 *
 * Only elections and partitions (`{group}.{partition}`), whose names come from the application's code, are tagged by
 * name; locks, whose names often come from data, are counted together ([ElectionKind] of the event). A name's
 * `simba.leadership` gauge exists from its first term until a term ends `STOPPED` (the election stopped, the
 * partition moved away, or the client closed), so stopped elections leave no series.
 */
class MicrometerSimbaEventListener(private val registry: MeterRegistry) : SimbaEventListener {
    companion object {
        const val SESSION_RENEW = "simba.session.renew"
        const val SESSION_STATE = "simba.session.state"
        const val LEADERSHIP = "simba.leadership"
        const val LOCKS_HELD = "simba.locks.held"
        const val REVOCATIONS = "simba.revocations"
        const val WORK = "simba.work"
        private const val NAME = "name"
    }

    private val state = AtomicReference(SessionStatus.OPENING)
    private val leaders = ConcurrentHashMap<LeaseName, Leader>()
    private val locksHeld = AtomicInteger()

    private class Leader(val held: AtomicInteger, val gauge: Gauge)
    private val renew = Timer.builder(SESSION_RENEW)
        .description("Successful session keep-alive round trips")
        .register(registry)

    init {
        Gauge.builder(LOCKS_HELD, locksHeld) { it.get().toDouble() }
            .description("Locks this node holds")
            .register(registry)
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
            is SimbaEvent.Elected -> if (event.kind == ElectionKind.LOCK) {
                locksHeld.incrementAndGet()
            } else {
                leader(event.name).held.set(1)
            }

            is SimbaEvent.Revoked -> revoked(event)

            is SimbaEvent.WorkCompleted -> Timer.builder(WORK)
                .description("Leader work runs")
                .tags(NAME, event.name.value, "outcome", event.outcome.tag())
                .register(registry)
                .record(event.duration)

            is SimbaEvent.AcquireFailed, is SimbaEvent.SessionLeaseShortened -> Unit
        }
    }

    private fun revoked(event: SimbaEvent.Revoked) {
        when {
            event.kind == ElectionKind.LOCK -> locksHeld.updateAndGet { (it - 1).coerceAtLeast(0) }
            event.reason == RevokeReason.STOPPED -> leaders.remove(event.name)?.let { registry.remove(it.gauge) }
            else -> leaders[event.name]?.held?.set(0)
        }
        Counter.builder(REVOCATIONS)
            .description("Terms this node lost or gave up")
            .tags("reason", event.reason.tag(), "kind", event.kind.tag())
            .register(registry)
            .increment()
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
