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

package me.ahoo.simba.spi

import me.ahoo.simba.SimbaException
import java.time.Duration

/**
 * Backend SPI: sessions, leases attached to sessions, and group membership.
 *
 * Inputs every backend validates the same way, rejecting others with [IllegalArgumentException]: a lease duration
 * is at least one millisecond ([requireLease]), a group name follows the [LeaseName] rules ([requireGroup]), and
 * [acquire] takes at most [MAX_BATCH] names.
 *
 * Rules for implementations:
 * - every method is one backend round trip (or one per slot), with no scheduling, no notification and no local
 *   state;
 * - every remaining duration is computed with the backend's clock and returned as a *duration*, never a timestamp;
 * - a lease's token is greater than zero, strictly increases with every new term of its name and never resets (a
 *   counter advancing by one, or a globally increasing id such as a Zookeeper czxid);
 * - a session expires once it was not kept alive for its lease, even when the transport keeps a connection alive on
 *   its own (heartbeats);
 * - an expired session is never revived: [keepAlive] answers [KeepAliveResult.Expired] and [acquire] throws
 *   [SessionExpiredException];
 * - hints only accelerate; losing one never affects correctness.
 */
interface LeaseBackend : AutoCloseable {
    val capabilities: Capabilities get() = Capabilities()

    /** Opens a new session that the backend expires [lease] after its last successful keep-alive. */
    fun openSession(lease: Duration, metadata: Map<String, String> = emptyMap()): SessionGrant

    /**
     * Extends [session] by [lease] together with every lease and membership it owns, and reports every lease it owns
     * in [KeepAliveResult.Live.owned]. [held] names the leases the caller believes it owns; it is only a hint (a
     * backend may refresh those first) and never narrows what is extended or reported: a lease acquired while this
     * call was in flight, or whose reply was lost, is extended and reported too.
     */
    fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult

    /**
     * Tries to acquire every name in [names] (at most [MAX_BATCH]) for [session]. A lease that is free, or owned by
     * an expired session, moves to [session] with a token greater than every earlier one of that name; a name
     * [session] already owns answers its current token. A granted lease lasts at least as long as [session] does at
     * the time of the call, also when it was already owned, until a keep-alive extends both.
     */
    fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult>

    /**
     * Frees each lease in [leases] that [session] still owns with exactly the given token, keeping the token.
     * A stale release (any other token) is a no-op, so a delayed release can never free a later term.
     */
    fun release(session: SessionId, leases: Map<LeaseName, Long>)

    /** Ends [session], freeing every lease and membership it owns. Idempotent. */
    fun closeSession(session: SessionId)

    fun join(session: SessionId, group: String)

    fun leave(session: SessionId, group: String)

    /** Live members of [group], sorted. */
    fun members(group: String): List<SessionId>

    /** Subscribes to optional [BackendHint]s; closing the result unsubscribes. */
    fun subscribe(listener: BackendHintListener): AutoCloseable = AutoCloseable {}

    override fun close() = Unit

    companion object {
        /** Most names one [acquire] takes. */
        const val MAX_BATCH: Int = 64

        /** Shortest lease duration a backend accepts. */
        @JvmStatic
        val MIN_LEASE: Duration = Duration.ofMillis(1)

        /** Answers [lease] when it is at least [MIN_LEASE], else throws [IllegalArgumentException]. */
        @JvmStatic
        fun requireLease(lease: Duration): Duration {
            require(lease >= MIN_LEASE) { "Lease [$lease] must be at least $MIN_LEASE." }
            return lease
        }

        /** Answers [group] when it follows the [LeaseName] rules, else throws [IllegalArgumentException]. */
        @JvmStatic
        fun requireGroup(group: String): String {
            try {
                LeaseName(group)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Group name [$group] must follow the lease name rules.", error)
            }
            return group
        }

        /** Throws [IllegalArgumentException] when [names] has more than [MAX_BATCH] names. */
        @JvmStatic
        fun requireBatch(names: Collection<LeaseName>) {
            require(names.size <= MAX_BATCH) { "At most $MAX_BATCH names per acquire, was ${names.size}." }
        }
    }
}

/** What a backend can do beyond the required contract. */
data class Capabilities(
    val releaseHints: Boolean = false,
    val membershipHints: Boolean = false,
)

/** A new session and the backend-measured time until it expires. */
data class SessionGrant(val id: SessionId, val remaining: Duration)

sealed interface KeepAliveResult {
    /**
     * The session lives for [remaining] more. [owned] lists every lease the session owns, with its token, whatever
     * `held` said: the caller revokes terms missing from it and releases leases it acquired without knowing, so an
     * incomplete list could hand an old token back to its holder.
     */
    data class Live(val remaining: Duration, val owned: Map<LeaseName, Long>) : KeepAliveResult

    /** The session expired; it will never be revived. */
    data object Expired : KeepAliveResult
}

sealed interface AcquireResult {
    /** The caller's session owns the lease in the term numbered [token]. */
    data class Granted(val token: Long) : AcquireResult

    /** Another live session owns the lease in the term numbered [token]. */
    data class HeldBy(val session: SessionId, val token: Long) : AcquireResult
}

/** Optional backend notification. */
sealed interface BackendHint {
    /** The lease [name] was released. */
    data class Released(val name: LeaseName) : BackendHint

    /** The members of [group] changed. */
    data class MembersChanged(val group: String) : BackendHint
}

fun interface BackendHintListener {
    fun onHint(hint: BackendHint)
}

/** The session is expired at the backend and will never be revived. */
class SessionExpiredException(val session: SessionId) : SimbaException("Session [$session] expired.")
