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
import me.ahoo.simba.event.ElectionStatus
import me.ahoo.simba.event.SimbaSnapshot
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health

/**
 * Health of the Simba session (ADR 0010 §9), from [Simba.snapshot]: `DOWN` once the session has not been live for
 * longer than the lease duration, so this node can neither keep nor win a term on any backend. Not leading is
 * healthy. Details list the elections and partitions this node leads; locks are only counted (their names come from
 * data).
 */
class SimbaHealthIndicator(private val snapshot: () -> SimbaSnapshot) :
    AbstractHealthIndicator("Simba health check failed") {
    constructor(simba: Simba) : this(simba::snapshot)

    override fun doHealthCheck(builder: Health.Builder) {
        val snapshot = snapshot()
        val leaders = snapshot.elections.filter { it.status == ElectionStatus.LEADER }
        if (snapshot.healthy) builder.up() else builder.down()
        builder.withDetail("session", snapshot.session.id?.value ?: "none")
            .withDetail("status", snapshot.session.status.name)
            .withDetail("validFor", "${snapshot.session.validFor.toMillis()}ms")
            .withDetail("notLiveFor", "${snapshot.session.notLiveFor.toMillis()}ms")
            .withDetail("leaseDuration", "${snapshot.leaseDuration.toMillis()}ms")
            .withDetail(
                "leading",
                leaders.filter { it.kind != ElectionKind.LOCK }.map { it.name.value }
            )
            .withDetail("locksHeld", leaders.count { it.kind == ElectionKind.LOCK })
    }
}
