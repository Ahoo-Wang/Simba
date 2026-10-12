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

import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.LeaseContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexRetrievalService
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Health of this node's Simba contention (ADR 0007), fed by the [ContendObserver] events of the backend factories.
 *
 * `DOWN` when a running lease-backend service (JDBC, Redis) had no successful backend round trip for more than
 * twice its `ttl + transition`: it can neither acquire nor keep a lease, whether its calls fail or hang. Not owning
 * a mutex is healthy. Zookeeper reports no round trips; its services add details only.
 *
 * @author ahoo wang
 */
class SimbaHealthIndicator(private val nanoTime: () -> Long = System::nanoTime) :
    AbstractHealthIndicator("Simba health check failed"),
    ContendObserver {
    companion object {
        private const val STALE_LEASES = 2
    }

    private val startedAt = ConcurrentHashMap<MutexContendService, Long>()
    private val mutexes = ConcurrentHashMap<String, MutexHealth>()

    override fun onStarted(service: MutexContendService) {
        startedAt[service] = nanoTime()
    }

    override fun onStopped(service: MutexContendService) {
        startedAt.remove(service)
        if (startedAt.keys.none { it.mutex == service.mutex }) {
            mutexes.remove(service.mutex)
        }
    }

    override fun onContend(mutex: String, renew: Boolean, durationNanos: Long, outcome: ContendOutcome) {
        val now = nanoTime()
        mutexes.compute(mutex) { _, health ->
            val current = health ?: MutexHealth()
            if (outcome == ContendOutcome.FAILED) {
                current.copy(failingSince = current.failingSince ?: now)
            } else {
                current.copy(lastSuccess = now, failingSince = null)
            }
        }
    }

    override fun onLeaseExpired(mutex: String) {
        val now = nanoTime()
        mutexes.compute(mutex) { _, health -> (health ?: MutexHealth()).copy(lastLeaseExpired = now) }
    }

    override fun doHealthCheck(builder: Health.Builder) {
        val now = nanoTime()
        startedAt.keys.removeIf { it.status == MutexRetrievalService.Status.INITIAL }
        var down = false
        val details = startedAt.entries
            .sortedWith(compareBy({ it.key.mutex }, { it.key.contenderId }))
            .map { (service, started) ->
                val detail = describe(service, started, now)
                if (detail["stale"] == true) {
                    down = true
                }
                detail
            }
        if (down) builder.down() else builder.up()
        builder.withDetail("mutexes", details)
    }

    private fun describe(service: MutexContendService, started: Long, now: Long): Map<String, Any> {
        val health = mutexes[service.mutex] ?: MutexHealth()
        val detail = linkedMapOf<String, Any>(
            "mutex" to service.mutex,
            "contenderId" to service.contenderId,
            "owner" to service.isOwner,
            "fencingToken" to service.fencingToken
        )
        if (service.isOwner) {
            detail["leaseRemaining"] = service.leaseRemaining.let {
                if (it == MutexContendService.UNBOUNDED_LEASE) "unbounded" else "${it.toMillis()}ms"
            }
        }
        val leaseMillis = (service as? LeaseContendService)?.leaseConfig?.leaseMillis ?: return detail
        val lastSuccess = health.lastSuccess?.takeIf { it - started > 0 } ?: started
        val sinceSuccessMillis = TimeUnit.NANOSECONDS.toMillis(now - lastSuccess)
        detail["sinceLastSuccess"] = "${sinceSuccessMillis}ms"
        health.failingSince?.let { detail["failingFor"] = "${TimeUnit.NANOSECONDS.toMillis(now - it)}ms" }
        health.lastLeaseExpired?.let {
            val ago = TimeUnit.NANOSECONDS.toMillis(now - it)
            if (ago <= leaseMillis) {
                detail["leaseExpiredAgo"] = "${ago}ms"
            }
        }
        detail["stale"] = sinceSuccessMillis > STALE_LEASES * leaseMillis
        return detail
    }

    /**
     * Contention facts of one mutex in `System.nanoTime()`.
     */
    private data class MutexHealth(
        val lastSuccess: Long? = null,
        val failingSince: Long? = null,
        val lastLeaseExpired: Long? = null
    )
}
