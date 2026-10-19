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
import java.util.Objects

/**
 * A point-in-time view of a [me.ahoo.simba.Simba] client (ADR 0010 §9), read by the Actuator endpoint and the health
 * indicator. Times are on the client's monotonic clock. Snapshot types are plain immutable classes with value
 * equality (no `copy` or destructuring), so later versions can add properties compatibly.
 *
 * @param leaseDuration the configured session lease duration.
 */
class SimbaSnapshot(
    val session: SessionSnapshot,
    val elections: List<ElectionSnapshot>,
    val leaseDuration: Duration,
) {
    /** `false` once the session has not been [SessionStatus.LIVE] for longer than [leaseDuration]. */
    val healthy: Boolean
        get() = session.notLiveFor <= leaseDuration
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SimbaSnapshot &&
            session == other.session &&
            elections == other.elections &&
            leaseDuration == other.leaseDuration

    override fun hashCode(): Int = Objects.hash(session, elections, leaseDuration)

    override fun toString(): String = "SimbaSnapshot(session=$session, elections=$elections, leaseDuration=$leaseDuration)"
}

/**
 * @param id the current session, `null` while none is open.
 * @param validFor time left before the session's local deadline; [Duration.ZERO] unless open.
 * @param notLiveFor how long the session has not been [SessionStatus.LIVE] (since the client started, or since it
 *   was last live); [Duration.ZERO] while live.
 */
class SessionSnapshot(
    val id: SessionId?,
    val status: SessionStatus,
    val validFor: Duration,
    val notLiveFor: Duration,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SessionSnapshot &&
            id == other.id &&
            status == other.status &&
            validFor == other.validFor &&
            notLiveFor == other.notLiveFor

    override fun hashCode(): Int = Objects.hash(id, status, validFor, notLiveFor)

    override fun toString(): String = "SessionSnapshot(id=$id, status=$status, validFor=$validFor, notLiveFor=$notLiveFor)"
}

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
class ElectionSnapshot(
    val name: LeaseName,
    val status: ElectionStatus,
    val token: Long,
    val remaining: Duration,
    val kind: ElectionKind = ElectionKind.ELECTION,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ElectionSnapshot &&
            name == other.name &&
            status == other.status &&
            token == other.token &&
            remaining == other.remaining &&
            kind == other.kind

    override fun hashCode(): Int = Objects.hash(name, status, token, remaining, kind)

    override fun toString(): String = "ElectionSnapshot(name=$name, status=$status, token=$token, remaining=$remaining, kind=$kind)"
}

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
