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

package me.ahoo.simba.event

import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import java.time.Duration

/**
 * A point-in-time view of a [me.ahoo.simba.Simba] client (ADR 0010 §9), read by the Actuator endpoint and the health
 * indicator. Times are on the client's monotonic clock.
 *
 * @param leaseDuration the configured session lease duration.
 */
data class SimbaSnapshot(
    val session: SessionSnapshot,
    val elections: List<ElectionSnapshot>,
    val leaseDuration: Duration,
) {
    /** `false` once the session has not been [SessionStatus.LIVE] for longer than [leaseDuration]. */
    val healthy: Boolean
        get() = session.notLiveFor <= leaseDuration
}

/**
 * @param id the current session, `null` while none is open.
 * @param validFor time left before the session's local deadline; [Duration.ZERO] unless open.
 * @param notLiveFor how long the session has not been [SessionStatus.LIVE] (since the client started, or since it
 *   was last live); [Duration.ZERO] while live.
 */
data class SessionSnapshot(
    val id: SessionId?,
    val status: SessionStatus,
    val validFor: Duration,
    val notLiveFor: Duration,
)

enum class SessionStatus {
    /** No session yet: opening one, or a new one after the last expired. */
    OPENING,

    /** The last keep-alive succeeded. */
    LIVE,

    /** The last keep-alive failed; the local deadline has not passed yet. */
    SUSPENDED,

    /** The client closed. */
    CLOSED,
}

/**
 * One started election (lock, schedule and partition elections included).
 *
 * @param token the fencing token of the current term; `0` without one.
 * @param remaining time left of the current term; [Duration.ZERO] without one.
 * @param kind which API started the election.
 */
data class ElectionSnapshot(
    val name: LeaseName,
    val status: ElectionStatus,
    val token: Long,
    val remaining: Duration,
    val kind: ElectionKind = ElectionKind.ELECTION,
)

enum class ElectionStatus {
    /** Contending for the lease. */
    CANDIDATE,

    /** Holding a valid term. */
    LEADER,

    /** Stepped down; not contending until the cooldown ends. */
    COOLDOWN,

    /** The term was stopped or stepped down; waiting for its work to exit before releasing the lease. */
    HANDOVER,
}
