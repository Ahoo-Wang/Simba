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

package me.ahoo.simba.testing

import me.ahoo.simba.SimbaException
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.BackendHintListener
import me.ahoo.simba.spi.Capabilities
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.Objects
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [LeaseBackend] in memory, with time read from [ticker] (the "backend clock"). Thread-safe; hints are delivered
 * synchronously after the call that caused them, outside the internal lock.
 *
 * By default leases belong to sessions: a lease is free when released or when its session expired. With
 * [selfExpiringLeases] a lease instead expires on its own, like on Redis: a grant lasts one full lease of its
 * session, or with [sessionBoundGrants] only as long as the session has left (as a single Redis node grants);
 * keep-alive extends every lease the session owns, acquiring an owned lease extends it the same way, and a lease
 * outlives an expired session until its own expiry ([builder] sets these modes). [beginSlotAcquire] splits an acquire in two steps, as on a Redis
 * Cluster. Either way a lease's token never resets, and expired sessions are forgotten on first sight and never
 * revived.
 */
class InMemoryLeaseBackend internal constructor(
    private val ticker: Ticker,
    private val selfExpiringLeases: Boolean,
    private val sessionBoundGrants: Boolean,
    private val sessionIds: () -> SessionId,
) : LeaseBackend {
    /**
     * A backend on [ticker] whose leases expire on their own with [selfExpiringLeases], each grant lasting one full
     * lease, and whose session ids come from [sessionIds]. [builder] also sets [sessionBoundGrants].
     */
    @JvmOverloads
    constructor(
        ticker: Ticker = Ticker.SYSTEM,
        selfExpiringLeases: Boolean = false,
        sessionIds: () -> SessionId = SessionId::random,
    ) : this(ticker, selfExpiringLeases, false, sessionIds)

    /** Builds an [InMemoryLeaseBackend]; every setting defaults as in the constructor. */
    class Builder internal constructor() {
        private var ticker: Ticker = Ticker.SYSTEM
        private var selfExpiringLeases: Boolean = false
        private var sessionBoundGrants: Boolean = false
        private var sessionIds: () -> SessionId = SessionId::random

        /** The backend clock. Default [Ticker.SYSTEM]. */
        fun ticker(ticker: Ticker) = apply { this.ticker = ticker }

        /** Whether leases expire on their own, as on Redis, instead of with their session. Default `false`. */
        fun selfExpiringLeases(selfExpiringLeases: Boolean) = apply { this.selfExpiringLeases = selfExpiringLeases }

        /**
         * With self-expiring leases, whether an [acquire] grant lasts only as long as the session has left, as on a
         * single Redis node, instead of one full lease. Default `false`.
         */
        fun sessionBoundGrants(sessionBoundGrants: Boolean) = apply { this.sessionBoundGrants = sessionBoundGrants }

        /** Where session ids come from; each must be new. Default [SessionId.random]. */
        fun sessionIds(sessionIds: () -> SessionId) = apply { this.sessionIds = sessionIds }

        /** Fails when [sessionBoundGrants] is set without [selfExpiringLeases]. */
        fun build(): InMemoryLeaseBackend {
            require(!sessionBoundGrants || selfExpiringLeases) { "sessionBoundGrants needs selfExpiringLeases." }
            return InMemoryLeaseBackend(ticker, selfExpiringLeases, sessionBoundGrants, sessionIds)
        }
    }

    companion object {
        /** A [Builder] with every setting at its default. */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    private class Session(val id: SessionId, var expiresAt: Long, var lease: Long)

    private class Lease(var session: SessionId?, var token: Long, var expiresAt: Long = 0)

    /** Owner of a lease as the backend sees it now. */
    class Owner(
        val session: SessionId,
        val token: Long,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Owner &&
                session == other.session &&
                token == other.token

        override fun hashCode(): Int = Objects.hash(session, token)

        override fun toString(): String = "Owner(session=$session, token=$token)"
    }

    private val lock = Any()
    private val sessions = HashMap<SessionId, Session>()
    private val leases = HashMap<LeaseName, Lease>()
    private val groups = HashMap<String, MutableSet<SessionId>>()
    private val listeners = CopyOnWriteArrayList<BackendHintListener>()

    override val capabilities: Capabilities = Capabilities(releaseHints = true, membershipHints = true)

    override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant =
        synchronized(lock) {
            LeaseBackend.requireLease(lease)
            val id = sessionIds()
            check(id !in sessions) { "Session id [$id] reused." }
            sessions[id] = Session(id, now() + lease.toNanos(), lease.toNanos())
            SessionGrant(id, lease)
        }

    override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult =
        withHints { hints ->
            LeaseBackend.requireLease(lease)
            val live = live(session, hints) ?: return@withHints KeepAliveResult.Expired
            live.expiresAt = now() + lease.toNanos()
            live.lease = lease.toNanos()
            val owned = owned(session)
            if (selfExpiringLeases) {
                owned.keys.forEach { leases.getValue(it).extend(live.expiresAt) }
            }
            KeepAliveResult.Live(lease, owned)
        }

    override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> =
        withHints { hints ->
            LeaseBackend.requireBatch(names)
            val live = live(session, hints) ?: throw SessionExpiredException(session)
            // A grant lasts as long as the session, or one full lease when leases expire on their own (unless bound).
            val until = if (selfExpiringLeases && !sessionBoundGrants) now() + live.lease else live.expiresAt
            grant(session, names, until, hints)
        }

    /**
     * The first step of an acquire split as on a Redis Cluster: checks that [session] is live and reads what it has
     * left and its lease, as the session slot's script does. [SlotAcquire.grant] takes the leases later, as the lease
     * slots' scripts do, with anything (a keep-alive, an expiry) in between.
     */
    fun beginSlotAcquire(session: SessionId, names: Set<LeaseName>): SlotAcquire =
        withHints { hints ->
            LeaseBackend.requireBatch(names)
            val live = live(session, hints) ?: throw SessionExpiredException(session)
            SlotAcquire(session, names, now(), live.expiresAt - now(), live.lease)
        }

    /** The second step of [beginSlotAcquire]. */
    inner class SlotAcquire internal constructor(
        private val session: SessionId,
        private val names: Set<LeaseName>,
        private val readAt: Long,
        private val remaining: Long,
        private val lease: Long,
    ) {
        /**
         * Grants [names] for one full session lease from now (it covers every keep-alive since the session was read),
         * or fails when the session's remaining time passed since it was read: the client then sends nothing.
         */
        fun grant(): Map<LeaseName, AcquireResult> =
            withHints { hints ->
                if (now() - readAt >= remaining) {
                    throw SimbaException("Acquiring $names for session [$session] came after its remaining time.")
                }
                grant(session, names, now() + lease, hints)
            }
    }

    private fun grant(
        session: SessionId,
        names: Set<LeaseName>,
        until: Long,
        hints: MutableList<BackendHint>,
    ): Map<LeaseName, AcquireResult> =
        names.associateWith { name ->
            val lease = leases.getOrPut(name) { Lease(null, 0) }
            val holder = holder(lease, hints)
            when {
                holder == session -> {
                    lease.extend(until)
                    AcquireResult.Granted(lease.token)
                }
                holder != null -> AcquireResult.HeldBy(holder, lease.token)
                else -> {
                    lease.session = session
                    lease.token++
                    lease.expiresAt = until
                    AcquireResult.Granted(lease.token)
                }
            }
        }

    override fun release(session: SessionId, leases: Map<LeaseName, Long>) {
        withHints { hints ->
            for ((name, token) in leases) {
                val lease = this.leases[name] ?: continue
                if (lease.session == session && lease.token == token) {
                    lease.session = null
                    hints += BackendHint.Released(name)
                }
            }
        }
    }

    override fun closeSession(session: SessionId) {
        withHints { hints -> end(session, hints, closing = true) }
    }

    override fun join(session: SessionId, group: String) {
        LeaseBackend.requireGroup(group)
        withHints { hints ->
            live(session, hints) ?: throw SessionExpiredException(session)
            if (groups.getOrPut(group) { LinkedHashSet() }.add(session)) {
                hints += BackendHint.MembersChanged(group)
            }
        }
    }

    override fun leave(session: SessionId, group: String) {
        LeaseBackend.requireGroup(group)
        withHints { hints ->
            if (groups[group]?.remove(session) == true) {
                hints += BackendHint.MembersChanged(group)
            }
        }
    }

    override fun members(group: String): List<SessionId> =
        withHints { hints ->
            LeaseBackend.requireGroup(group)
            groups[group].orEmpty().toList().filter { live(it, hints) != null }.sortedBy { it.value }
        }

    override fun subscribe(listener: BackendHintListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** The current owner of [name]: a live session, or with self-expiring leases one whose lease has not expired. */
    fun owner(name: LeaseName): Owner? =
        withHints { hints ->
            val lease = leases[name] ?: return@withHints null
            val session = holder(lease, hints) ?: return@withHints null
            Owner(session, lease.token)
        }

    /**
     * The owner of [name] as [owner] reports it, without side effects: an expired session is not ended and no hint is
     * sent, so a checker reading it cannot change the run it checks.
     */
    fun peekOwner(name: LeaseName): Owner? =
        synchronized(lock) {
            val lease = leases[name] ?: return null
            val session = lease.session ?: return null
            val live = sessions[session]?.takeIf { now() - it.expiresAt < 0 }
            val holds = if (selfExpiringLeases) now() - lease.expiresAt < 0 else live != null
            if (holds) Owner(session, lease.token) else null
        }

    /**
     * The backend time (on [ticker]) at which [name] stops being owned by its current owner unless extended: its
     * session's expiry, or with self-expiring leases its own; `null` when nobody owns it. Without side effects.
     */
    fun peekExpiry(name: LeaseName): Long? =
        synchronized(lock) {
            peekOwner(name) ?: return null
            val lease = leases.getValue(name)
            if (selfExpiringLeases) lease.expiresAt else sessions.getValue(lease.session!!).expiresAt
        }

    /**
     * Expires [session] now on the backend's own initiative, as a server ending a session early does: its leases are
     * freed with it, or with self-expiring leases they keep running until their own expiry.
     */
    fun expireSession(session: SessionId) {
        withHints { hints ->
            sessions[session]?.let { it.expiresAt = now() }
            live(session, hints)
        }
    }

    /**
     * Forgets [name] entirely, as a store losing data does (an evicted key, a failover to a replica that missed the
     * write): the lease is free and its token starts again from zero, breaking the never-reset rule on purpose.
     */
    fun loseLease(name: LeaseName) {
        withHints { hints ->
            if (leases.remove(name)?.session != null) {
                hints += BackendHint.Released(name)
            }
        }
    }

    /** The latest token of [name]; `0` when never acquired. */
    fun token(name: LeaseName): Long = synchronized(lock) { leases[name]?.token ?: 0 }

    private fun now(): Long = ticker.nanoTime()

    private fun owned(session: SessionId): Map<LeaseName, Long> =
        leases.entries
            .filter { it.value.session == session && (!selfExpiringLeases || now() - it.value.expiresAt < 0) }
            .associate { it.key to it.value.token }

    /** The session holding [lease]: its own expiry decides with self-expiring leases, else its session's liveness. */
    private fun holder(lease: Lease, hints: MutableList<BackendHint>): SessionId? {
        val session = lease.session ?: return null
        if (!selfExpiringLeases) {
            return live(session, hints)?.id
        }
        live(session, hints)
        return session.takeIf { now() - lease.expiresAt < 0 }
    }

    private fun Lease.extend(until: Long) {
        if (until - expiresAt > 0) {
            expiresAt = until
        }
    }

    /** The session if live; an expired one is ended (its leases freed) and forgotten. */
    private fun live(id: SessionId, hints: MutableList<BackendHint>): Session? {
        val session = sessions[id] ?: return null
        if (now() - session.expiresAt < 0) {
            return session
        }
        end(id, hints)
        return null
    }

    /** Ends [id]: closing frees its leases, expiry too unless leases expire on their own. */
    private fun end(id: SessionId, hints: MutableList<BackendHint>, closing: Boolean = false) {
        sessions.remove(id)
        for ((name, lease) in leases) {
            if (lease.session == id && (closing || !selfExpiringLeases)) {
                lease.session = null
                hints += BackendHint.Released(name)
            }
        }
        for ((group, members) in groups) {
            if (members.remove(id)) {
                hints += BackendHint.MembersChanged(group)
            }
        }
    }

    private fun <T> withHints(block: (MutableList<BackendHint>) -> T): T {
        val hints = ArrayList<BackendHint>()
        val result = synchronized(lock) { block(hints) }
        hints.forEach { hint -> listeners.forEach { it.onHint(hint) } }
        return result
    }
}
