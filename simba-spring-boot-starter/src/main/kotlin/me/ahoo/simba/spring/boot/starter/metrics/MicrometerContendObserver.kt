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
import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.WorkOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records Simba events as Micrometer meters, tagged by mutex only to keep cardinality bounded:
 *
 * | Meter | Type | Tags |
 * |---|---|---|
 * | `simba.mutex.owner` | gauge: local contenders owning the mutex (0 or 1) | `mutex` |
 * | `simba.mutex.ownership.changes` | counter | `mutex`, `change` = `acquired` / `released` |
 * | `simba.mutex.contend` | timer: backend round trips | `mutex`, `operation` = `acquire` / `renew`, `outcome` |
 * | `simba.mutex.lease.expired` | counter: watchdog revocations | `mutex` |
 * | `simba.scheduler.work` | timer: leader-only work runs | `mutex`, `outcome` |
 *
 * @author ahoo wang
 */
class MicrometerContendObserver(private val registry: MeterRegistry) : ContendObserver {
    companion object {
        const val OWNER = "simba.mutex.owner"
        const val OWNERSHIP_CHANGES = "simba.mutex.ownership.changes"
        const val CONTEND = "simba.mutex.contend"
        const val LEASE_EXPIRED = "simba.mutex.lease.expired"
        const val WORK = "simba.scheduler.work"
        private const val MUTEX = "mutex"
    }

    private val owners = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Registered on the first event of a mutex: every lease backend contends right after start; on Zookeeper the
     * gauge appears on the first acquisition.
     */
    private fun owners(mutex: String): AtomicInteger {
        return owners.computeIfAbsent(mutex) { key ->
            AtomicInteger().also { count ->
                Gauge.builder(OWNER, count) { it.get().toDouble() }
                    .description("Local contenders owning the mutex (0 or 1)")
                    .tag(MUTEX, key)
                    .register(registry)
            }
        }
    }

    private fun ownershipChange(mutex: String, change: String) {
        Counter.builder(OWNERSHIP_CHANGES)
            .description("Ownership changes of this node")
            .tags(MUTEX, mutex, "change", change)
            .register(registry)
            .increment()
    }

    override fun onContend(mutex: String, renew: Boolean, durationNanos: Long, outcome: ContendOutcome) {
        owners(mutex)
        Timer.builder(CONTEND)
            .description("Backend contention round trips")
            .tags(MUTEX, mutex, "operation", if (renew) "renew" else "acquire", "outcome", outcome.tag())
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    override fun onAcquired(mutex: String) {
        owners(mutex).incrementAndGet()
        ownershipChange(mutex, "acquired")
    }

    override fun onReleased(mutex: String) {
        owners(mutex).updateAndGet { (it - 1).coerceAtLeast(0) }
        ownershipChange(mutex, "released")
    }

    override fun onLeaseExpired(mutex: String) {
        Counter.builder(LEASE_EXPIRED)
            .description("Ownership revoked because the lease ended without renewal")
            .tag(MUTEX, mutex)
            .register(registry)
            .increment()
    }

    override fun onWork(mutex: String, durationNanos: Long, outcome: WorkOutcome) {
        Timer.builder(WORK)
            .description("Leader-only scheduled work runs")
            .tags(MUTEX, mutex, "outcome", outcome.tag())
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }

    private fun Enum<*>.tag(): String = name.lowercase()
}
