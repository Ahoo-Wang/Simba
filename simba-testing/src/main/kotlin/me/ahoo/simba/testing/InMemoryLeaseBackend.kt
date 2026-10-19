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
 * Leases belong to sessions: a lease is free when released or when its session expired, and a lease's token never
 * resets. Expired sessions are forgotten on first sight and never revived.
 */
class InMemoryLeaseBackend(
    private val ticker: Ticker = Ticker.SYSTEM,
    private val sessionIds: () -> SessionId = SessionId::random,
) : LeaseBackend {
    private class Session(val id: SessionId, var expiresAt: Long)

    private class Lease(var session: SessionId?, var token: Long)

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

    override val capabilities: Capabilities =
        Capabilities(releaseHints = true, membershipHints = true, maxBatch = Int.MAX_VALUE)

    override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant =
        synchronized(lock) {
            val id = sessionIds()
            check(id !in sessions) { "Session id [$id] reused." }
            sessions[id] = Session(id, now() + lease.toNanos())
            SessionGrant(id, lease)
        }

    override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult =
        withHints { hints ->
            val live = live(session, hints) ?: return@withHints KeepAliveResult.Expired
            live.expiresAt = now() + lease.toNanos()
            KeepAliveResult.Live(lease, owned(session))
        }

    override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> =
        withHints { hints ->
            live(session, hints) ?: throw SessionExpiredException(session)
            names.associateWith { name ->
                val lease = leases.getOrPut(name) { Lease(null, 0) }
                val holder = lease.session?.let { live(it, hints) }
                when {
                    lease.session == session -> AcquireResult.Granted(lease.token)
                    holder != null -> AcquireResult.HeldBy(holder.id, lease.token)
                    else -> {
                        lease.session = session
                        lease.token++
                        AcquireResult.Granted(lease.token)
                    }
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
        withHints { hints -> end(session, hints) }
    }

    override fun join(session: SessionId, group: String) {
        withHints { hints ->
            live(session, hints) ?: throw SessionExpiredException(session)
            if (groups.getOrPut(group) { LinkedHashSet() }.add(session)) {
                hints += BackendHint.MembersChanged(group)
            }
        }
    }

    override fun leave(session: SessionId, group: String) {
        withHints { hints ->
            if (groups[group]?.remove(session) == true) {
                hints += BackendHint.MembersChanged(group)
            }
        }
    }

    override fun members(group: String): List<SessionId> =
        withHints { hints ->
            groups[group].orEmpty().toList().filter { live(it, hints) != null }.sortedBy { it.value }
        }

    override fun subscribe(listener: BackendHintListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** The current owner of [name], if its session is live. */
    fun owner(name: LeaseName): Owner? =
        withHints { hints ->
            val lease = leases[name] ?: return@withHints null
            val session = lease.session?.let { live(it, hints) } ?: return@withHints null
            Owner(session.id, lease.token)
        }

    /** The latest token of [name]; `0` when never acquired. */
    fun token(name: LeaseName): Long = synchronized(lock) { leases[name]?.token ?: 0 }

    private fun now(): Long = ticker.nanoTime()

    private fun owned(session: SessionId): Map<LeaseName, Long> =
        leases.entries.filter { it.value.session == session }.associate { it.key to it.value.token }

    /** The session if live; an expired one is ended (its leases freed) and forgotten. */
    private fun live(id: SessionId, hints: MutableList<BackendHint>): Session? {
        val session = sessions[id] ?: return null
        if (now() - session.expiresAt < 0) {
            return session
        }
        end(id, hints)
        return null
    }

    private fun end(id: SessionId, hints: MutableList<BackendHint>) {
        sessions.remove(id)
        for ((name, lease) in leases) {
            if (lease.session == id) {
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
