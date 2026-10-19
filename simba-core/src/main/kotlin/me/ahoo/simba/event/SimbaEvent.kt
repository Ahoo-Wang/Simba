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

import me.ahoo.simba.RevokeReason
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import java.time.Duration
import java.util.Objects

/**
 * Something the engine did, for metrics and logs. Events are plain immutable classes with value equality (no
 * `copy` or destructuring), so later versions can add properties without breaking binary compatibility.
 */
sealed interface SimbaEvent {
    /**
     * A new session was opened.
     */
    class SessionOpened(
        val session: SessionId,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is SessionOpened &&
                session == other.session

        override fun hashCode(): Int = Objects.hashCode(session)

        override fun toString(): String = "SessionOpened(session=$session)"
    }

    /**
     * A keep-alive succeeded after [latency].
     */
    class SessionRenewed(
        val session: SessionId,
        val latency: Duration,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is SessionRenewed &&
                session == other.session &&
                latency == other.latency

        override fun hashCode(): Int = Objects.hash(session, latency)

        override fun toString(): String = "SessionRenewed(session=$session, latency=$latency)"
    }

    /**
     * A keep-alive failed while the local deadline has not passed yet.
     */
    class SessionSuspended(
        val session: SessionId,
        val cause: Throwable,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is SessionSuspended &&
                session == other.session &&
                cause == other.cause

        override fun hashCode(): Int = Objects.hash(session, cause)

        override fun toString(): String = "SessionSuspended(session=$session, cause=$cause)"
    }

    /**
     * The session ended. [reason] is only ever [RevokeReason.SESSION_EXPIRED] (the backend reported it expired) or
     * [RevokeReason.DEADLINE_PASSED] (its local deadline passed without a successful keep-alive).
     */
    class SessionExpired(
        val session: SessionId,
        val reason: RevokeReason,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is SessionExpired &&
                session == other.session &&
                reason == other.reason

        override fun hashCode(): Int = Objects.hash(session, reason)

        override fun toString(): String = "SessionExpired(session=$session, reason=$reason)"
    }

    /**
     * The backend grants [session] less time ([remaining]) than the configured renew interval allows, for example a
     * Zookeeper server capping the session timeout: the client keeps the session alive every [renewEvery] (a third of
     * the local validity) instead. Published once per session.
     */
    class SessionLeaseShortened(
        val session: SessionId,
        val remaining: Duration,
        val renewEvery: Duration,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is SessionLeaseShortened &&
                session == other.session &&
                remaining == other.remaining &&
                renewEvery == other.renewEvery

        override fun hashCode(): Int = Objects.hash(session, remaining, renewEvery)

        override fun toString(): String = "SessionLeaseShortened(session=$session, remaining=$remaining, renewEvery=$renewEvery)"
    }

    /**
     * The client closed; [session] is the session it closed, `null` when none was open. Published once.
     */
    class SessionClosed(
        val session: SessionId?,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is SessionClosed &&
                session == other.session

        override fun hashCode(): Int = Objects.hashCode(session)

        override fun toString(): String = "SessionClosed(session=$session)"
    }

    /**
     * A term began; [kind] tells which API started the election, so metrics need not label by [name].
     */
    class Elected(
        val name: LeaseName,
        val token: Long,
        val kind: ElectionKind = ElectionKind.ELECTION,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Elected &&
                name == other.name &&
                token == other.token &&
                kind == other.kind

        override fun hashCode(): Int = Objects.hash(name, token, kind)

        override fun toString(): String = "Elected(name=$name, token=$token, kind=$kind)"
    }

    /**
     * A term ended; [kind] as in [Elected].
     */
    class Revoked(
        val name: LeaseName,
        val token: Long,
        val reason: RevokeReason,
        val kind: ElectionKind = ElectionKind.ELECTION,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Revoked &&
                name == other.name &&
                token == other.token &&
                reason == other.reason &&
                kind == other.kind

        override fun hashCode(): Int = Objects.hash(name, token, reason, kind)

        override fun toString(): String = "Revoked(name=$name, token=$token, reason=$reason, kind=$kind)"
    }

    /**
     * An acquire call failed (not "held by someone else", which is normal); [kind] as in [Elected].
     */
    class AcquireFailed(
        val name: LeaseName,
        val cause: Throwable,
        val kind: ElectionKind = ElectionKind.ELECTION,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is AcquireFailed &&
                name == other.name &&
                cause == other.cause &&
                kind == other.kind

        override fun hashCode(): Int = Objects.hash(name, cause, kind)

        override fun toString(): String = "AcquireFailed(name=$name, cause=$cause, kind=$kind)"
    }

    /**
     * One run of leader work finished: the whole block of `runWhileLeader`, or one execution of a schedule. Work
     * failures never affect the election; they are logged and reported here with their [cause].
     *
     * @param token the fencing token of the term the work ran in.
     * @param duration how long the run took, on the client's monotonic clock.
     * @param kind as in [Elected].
     */
    class WorkCompleted(
        val name: LeaseName,
        val token: Long,
        val outcome: WorkOutcome,
        val duration: Duration,
        val cause: Throwable? = null,
        val kind: ElectionKind = ElectionKind.ELECTION,
    ) : SimbaEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is WorkCompleted &&
                name == other.name &&
                token == other.token &&
                outcome == other.outcome &&
                duration == other.duration &&
                cause == other.cause &&
                kind == other.kind

        override fun hashCode(): Int = Objects.hash(name, token, outcome, duration, cause, kind)

        override fun toString(): String = "WorkCompleted(name=$name, token=$token, outcome=$outcome, duration=$duration, cause=$cause, kind=$kind)"
    }
}

/** Which API started an election. */
enum class ElectionKind {
    /** `Simba.election`: listeners, `runWhileLeader` and schedules. */
    ELECTION,

    /** `Simba.lock`. */
    LOCK,

    /** A partition of `Simba.group`. */
    GROUP,
}

/** How a run of leader work ended. */
enum class WorkOutcome {
    /** Returned normally. */
    COMPLETED,

    /** Threw while its term was still valid. */
    FAILED,

    /** Ended by the end of its term: threw after the term was revoked, or found it expired. */
    CANCELLED,
}

/** Receives [SimbaEvent]s; exceptions are logged and ignored. */
fun interface SimbaEventListener {
    fun onEvent(event: SimbaEvent)
}
