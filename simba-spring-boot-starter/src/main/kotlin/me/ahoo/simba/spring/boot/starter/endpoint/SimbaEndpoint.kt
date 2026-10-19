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

import me.ahoo.simba.Simba
import me.ahoo.simba.event.ElectionSnapshot
import me.ahoo.simba.event.SimbaSnapshot
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector

/**
 * Read-only view of [Simba.snapshot]: `GET /actuator/simba` (the session and every started election) and
 * `/actuator/simba/{name}` (one election, 404 when not started here).
 */
@Endpoint(id = SimbaEndpoint.ID)
class SimbaEndpoint(private val simba: Simba) {
    companion object {
        const val ID = "simba"
    }

    @ReadOperation
    fun snapshot(): SnapshotDescriptor = SnapshotDescriptor.of(simba.snapshot())

    @ReadOperation
    fun election(@Selector name: String): ElectionDescriptor? =
        simba.snapshot().elections.firstOrNull { it.name.value == name }?.let(ElectionDescriptor::of)

    data class SnapshotDescriptor(val session: SessionDescriptor, val elections: List<ElectionDescriptor>) {
        companion object {
            fun of(snapshot: SimbaSnapshot): SnapshotDescriptor =
                SnapshotDescriptor(
                    SessionDescriptor(
                        id = snapshot.session.id?.value,
                        status = snapshot.session.status.name,
                        validForMillis = snapshot.session.validFor.toMillis(),
                        notLiveForMillis = snapshot.session.notLiveFor.toMillis(),
                    ),
                    snapshot.elections.map(ElectionDescriptor::of),
                )
        }
    }

    /** @param id the backend session, `null` while none is open. */
    data class SessionDescriptor(
        val id: String?,
        val status: String,
        val validForMillis: Long,
        val notLiveForMillis: Long,
    )

    /** @param token the fencing token of the current term, `0` without one. */
    data class ElectionDescriptor(
        val name: String,
        val status: String,
        val token: Long,
        val remainingMillis: Long,
        /** `ELECTION`, `LOCK` or `GROUP` (a partition): which API started it. */
        val kind: String,
    ) {
        companion object {
            fun of(election: ElectionSnapshot): ElectionDescriptor =
                ElectionDescriptor(
                    election.name.value,
                    election.status.name,
                    election.token,
                    election.remaining.toMillis(),
                    election.kind.name,
                )
        }
    }
}
