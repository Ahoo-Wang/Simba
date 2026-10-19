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
     * Extends [session] by [lease] together with every lease it owns. [held] names the leases the caller believes it
     * owns; backends whose leases expire on their own refresh exactly those.
     */
    fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult

    /**
     * Tries to acquire every name in [names] for [session]. A lease that is free, or owned by an expired session,
     * moves to [session] with a token greater than every earlier one of that name; a name [session] already owns
     * answers its current token.
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
}

/** What a backend can do beyond the required contract. */
data class Capabilities(
    val releaseHints: Boolean = false,
    val membershipHints: Boolean = false,
    val maxBatch: Int = 1,
)

/** A new session and the backend-measured time until it expires. */
data class SessionGrant(val id: SessionId, val remaining: Duration)

sealed interface KeepAliveResult {
    /**
     * The session lives for [remaining] more. [owned] lists the leases the session owns, with their tokens: at least
     * those in `held`, and every one when leases do not expire on their own (so the caller can release leases it
     * acquired without knowing).
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
