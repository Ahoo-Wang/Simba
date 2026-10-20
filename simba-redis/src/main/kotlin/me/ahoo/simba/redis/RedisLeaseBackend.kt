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

package me.ahoo.simba.redis

import io.github.oshai.kotlinlogging.KotlinLogging
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

/**
 * [LeaseBackend] on Redis 7 Lua scripts (ADR 0010 §7.2), driven through [RedisScripting].
 *
 * - A lease is a hash `simba:lease:{name}` = `{session, token, expires}` that never expires, so its token never
 *   resets; release clears `session` only. `expires` is Redis time (`TIME`): a lease is free once it passed, so
 *   leases expire on their own.
 * - A session is a hash `simba:session:{sid}` with `PX` = lease: keep-alive, acquire and join fail once it is gone,
 *   so an expired session is never revived. Its lease index (a sorted set of the lease keys it tried, scored by the
 *   attempt's Redis time) and group index live in the same slot.
 * - Keep-alive extends and reports every lease of the lease index the session still holds, whatever `held` says, so
 *   [KeepAliveResult.Live.owned] is complete; acquire extends a lease the session already holds as well. A new term
 *   lasts as long as its session.
 * - Group members are sorted sets `simba:group:{group}` scored by expiry in Redis time, extended by keep-alive.
 * - Release publishes `released` on the lease key's channel, the only broadcast ([BackendHint.Released]).
 * - Closing a session deletes it first, then frees its indexed leases and memberships, then drops its indexes:
 *   repeating a close that failed halfway finishes it.
 *
 * On a single node or Sentinel, acquire and join are one script call; keep-alive is one, plus one more when the
 * session has indexed leases beyond `held` or joined groups. With [routeBySlot] (default on a Redis Cluster) a call
 * first touches the session's slot, then runs one script per hash slot of the remaining keys.
 *
 * A script after the session's may run long after it (a starved thread, a driver holding commands until it
 * reconnects), when the session may have died. Only durations cross nodes, never a node's clock reading, so a fixed
 * skew between node clocks changes nothing:
 * - Acquire and join grant one full session lease on the lease node's own clock, as 5.0 did: a keep-alive that ran
 *   between the session call and the lease call extended the session but not the lease it did not hold yet, and the
 *   engine adopts the term with the deadline of that keep-alive, so a grant bounded by what the session had left at
 *   the session call could end while its holder's term is still valid. The price is liveness only: a grant to a
 *   session that died meanwhile outlives it by up to one lease.
 * - Keep-alive's later scripts carry what the session has left: its new remaining time minus the time measured on
 *   [ticker] since the session call answered. A lease node extends nothing beyond its own now plus that, so a lease
 *   lasts at least as long as its session and outlasts it by at most the delay before the script ran.
 * - Acquire and join send nothing once the time since the session call was sent reached the session's remaining
 *   time, and fail. An acquire whose reply arrives later than that releases the terms it began and fails, even when
 *   other slots granted theirs: a late grant may have missed the session's index (keep-alive drops a lost key one
 *   lease after its attempt), and [KeepAliveResult.Live.owned] would never report it. Only when that release fails
 *   or the reply is lost does such a lease stay unreported, until it expires on its own.
 *
 * Redis replication is asynchronous: after a failover a replica may have missed a token increment, so a token can go
 * back. [waitReplicas] narrows that window (not on a Cluster); otherwise prefer JDBC or Zookeeper for strict fencing.
 *
 * @param waitReplicas when set, an acquisition that began a term fails unless that many replicas acknowledged it.
 * @param routeBySlot whether to split script calls by hash slot.
 * @param ticker measures the time between the scripts of one call (tests substitute a virtual one).
 */
class RedisLeaseBackend internal constructor(
    private val scripting: RedisScripting,
    private val waitReplicas: ReplicaWait?,
    private val routeBySlot: Boolean,
    private val ticker: Ticker,
) : LeaseBackend {
    /** Measures time on [Ticker.SYSTEM]. */
    @JvmOverloads
    constructor(
        scripting: RedisScripting,
        waitReplicas: ReplicaWait? = null,
        routeBySlot: Boolean = scripting.clustered,
    ) : this(scripting, waitReplicas, routeBySlot, Ticker.SYSTEM)

    companion object {
        private val log = KotlinLogging.logger {}
        private const val EXPIRED = -1L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val WITH_SESSION = "1"
        private const val WITHOUT_SESSION = "0"
        private const val ANY_TOKEN = "*"
        private const val RELEASED = "released"
        private const val ACQUIRE_FIELDS = 3
        private const val ACQUIRE_HEADER = 2

        private val OPEN = LeaseScript.load("lease_session_open")
        private val SESSION_KEEP_ALIVE = LeaseScript.load("lease_session_keep_alive")
        private val KEEP_ALIVE = LeaseScript.load("lease_keep_alive")
        private val FORGET = LeaseScript.load("lease_session_forget")
        private val ACQUIRE = LeaseScript.load("lease_acquire")
        private val RELEASE = LeaseScript.load("lease_release")
        private val CLOSE = LeaseScript.load("lease_session_close")
        private val DROP = LeaseScript.load("lease_session_drop")
        private val JOIN = LeaseScript.load("lease_group_join")
        private val LEAVE = LeaseScript.load("lease_group_leave")
        private val MEMBERS = LeaseScript.load("lease_group_members")
    }

    init {
        require(waitReplicas == null || !scripting.clustered) { "waitReplicas is not supported on a Redis Cluster." }
    }

    override val capabilities: Capabilities = Capabilities(releaseHints = true)

    override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant {
        val millis = LeaseBackend.requireLease(lease).toMillis()
        val id = SessionId.random()
        val args = listOf(millis.toString()) + metadata.flatMap { (key, value) -> listOf(key, value) }
        eval(OPEN, listOf(RedisLeaseKeys.session(id)), args)
        log.debug { "Opened session [$id] on Redis for $lease." }
        return SessionGrant(id, Duration.ofMillis(millis))
    }

    override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult {
        val millis = LeaseBackend.requireLease(lease).toMillis()
        val inline = if (routeBySlot) emptyList() else held.map(RedisLeaseKeys::lease)
        val reply = eval(SESSION_KEEP_ALIVE, sessionKeys(session) + inline, listOf(session.value, millis.toString()))
        val answered = ticker.nanoTime()
        if (reply.long(0) == EXPIRED) {
            log.debug { "Session [$session] expired on Redis." }
            return KeepAliveResult.Expired
        }
        val now = reply.long(1)
        var index = 2
        val tokens = HashMap<String, Long>()
        inline.forEach { tokens[it] = reply.long(index++) }
        val others = LinkedHashMap<String, String>()
        repeat(reply.long(index++).toInt()) {
            others[reply[index] as String] = reply[index + 1] as String
            index += 2
        }
        val groups = reply.drop(index).mapTo(LinkedHashSet()) { it as String }
        tokens += extend(session, millis, answered, others.keys.toList(), groups)
        if (routeBySlot) {
            forget(session, others.filter { (key, score) -> tokens[key] == 0L && now - score.toLong() >= millis })
        }
        val owned = buildMap {
            for ((key, token) in tokens) {
                if (token > 0) {
                    put(checkNotNull(RedisLeaseKeys.leaseOf(key)) { "Not a lease key: [$key]." }, token)
                }
            }
        }
        return KeepAliveResult.Live(Duration.ofMillis(millis), owned)
    }

    override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> {
        LeaseBackend.requireBatch(names)
        val keys = names.map(RedisLeaseKeys::lease)
        val replies: List<Pair<List<String>, List<Any?>>>
        val replicas: Int?
        if (routeBySlot) {
            val sessionReply = sessionCall(ACQUIRE, session, RedisLeaseKeys.sessionLeases(session), keys)
            val args = listOf(WITHOUT_SESSION, session.value, sessionReply.grantFor("Acquiring $names").toString())
            val calls = routed(keys) { ScriptCall(ACQUIRE, it, args) }
            val result = if (calls.isEmpty()) ScriptReplies(emptyList()) else scripting.eval(calls, waitReplicas)
            replies = calls.map { it.keys }.zip(result.replies)
            if (sessionReply.late()) {
                abandon(session, names, replies)
            }
            replicas = result.replicas
        } else {
            val result = scripting.eval(
                listOf(
                    ScriptCall(
                        ACQUIRE,
                        listOf(RedisLeaseKeys.session(session), RedisLeaseKeys.sessionLeases(session)) + keys,
                        listOf(WITH_SESSION, session.value, "0") + keys
                    )
                ),
                waitReplicas
            )
            val reply = result.replies.single()
            if (reply.long(0) == EXPIRED) {
                throw SessionExpiredException(session)
            }
            replies = listOf(keys to reply)
            replicas = result.replicas
        }
        val byKey = HashMap<String, AcquireResult>()
        val begun = LinkedHashMap<LeaseName, Long>()
        val nameOf = names.associateBy(RedisLeaseKeys::lease)
        for ((callKeys, reply) in replies) {
            callKeys.forEachIndexed { index, key ->
                val offset = ACQUIRE_HEADER + index * ACQUIRE_FIELDS
                val token = reply.long(offset)
                val holder = reply[offset + 1] as String
                byKey[key] = if (holder == session.value) {
                    if (reply.long(offset + 2) == 1L) {
                        begun[nameOf.getValue(key)] = token
                    }
                    AcquireResult.Granted(token)
                } else {
                    AcquireResult.HeldBy(SessionId(holder), token)
                }
            }
        }
        if (waitReplicas != null && begun.isNotEmpty() && (replicas ?: 0) < waitReplicas.replicas) {
            unreplicated(session, begun, replicas ?: 0)
        }
        return names.associateWith { byKey.getValue(RedisLeaseKeys.lease(it)) }
    }

    override fun release(session: SessionId, leases: Map<LeaseName, Long>) {
        val tokens = leases.mapKeys { (name, _) -> RedisLeaseKeys.lease(name) }
        val calls = routed(tokens.keys.toList()) { keys ->
            ScriptCall(RELEASE, keys, listOf(session.value) + keys.map { tokens.getValue(it).toString() })
        }
        if (calls.isNotEmpty()) {
            scripting.eval(calls)
        }
    }

    override fun closeSession(session: SessionId) {
        val reply = eval(CLOSE, sessionKeys(session), emptyList())
        val leaseCount = reply.long(0).toInt()
        val leaseKeys = reply.subList(1, 1 + leaseCount).map { it as String }
        val groupKeys = reply.drop(1 + leaseCount).map { it as String }
        val calls = routed(leaseKeys) { keys -> ScriptCall(RELEASE, keys, listOf(session.value) + keys.map { ANY_TOKEN }) } +
            routed(groupKeys) { keys -> ScriptCall(LEAVE, keys, listOf(session.value)) }
        if (calls.isNotEmpty()) {
            scripting.eval(calls)
        }
        // Only once everything indexed was freed: a close that failed before this point finds the indexes again.
        eval(DROP, listOf(RedisLeaseKeys.sessionLeases(session), RedisLeaseKeys.sessionGroups(session)), emptyList())
        log.debug { "Closed session [$session] on Redis." }
    }

    override fun join(session: SessionId, group: String) {
        val key = RedisLeaseKeys.group(LeaseBackend.requireGroup(group))
        if (routeBySlot) {
            val sessionReply = sessionCall(JOIN, session, RedisLeaseKeys.sessionGroups(session), listOf(key))
            val lease = sessionReply.grantFor("Joining [$group]")
            eval(JOIN, listOf(key), listOf(WITHOUT_SESSION, session.value, lease.toString()))
            return
        }
        val reply = eval(
            JOIN,
            listOf(RedisLeaseKeys.session(session), RedisLeaseKeys.sessionGroups(session), key),
            listOf(WITH_SESSION, session.value, "0", key)
        )
        if (reply.long(0) == EXPIRED) {
            throw SessionExpiredException(session)
        }
    }

    override fun leave(session: SessionId, group: String) {
        eval(LEAVE, listOf(RedisLeaseKeys.group(LeaseBackend.requireGroup(group))), listOf(session.value))
    }

    override fun members(group: String): List<SessionId> =
        eval(MEMBERS, listOf(RedisLeaseKeys.group(LeaseBackend.requireGroup(group))), emptyList())
            .map { SessionId(it as String) }
            .sortedBy { it.value }

    override fun subscribe(listener: BackendHintListener): AutoCloseable =
        scripting.subscribe(RedisLeaseKeys.LEASE_CHANNELS) { channel, message ->
            val name = RedisLeaseKeys.leaseOf(channel)
            if (message == RELEASED && name != null) {
                try {
                    listener.onHint(BackendHint.Released(name))
                } catch (error: RuntimeException) {
                    log.warn(error) { "Hint listener failed on the release of [$name]." }
                }
            }
        }

    /**
     * Extends the indexed lease keys [leaseKeys] the session still holds and its memberships in [groupKeys], after
     * the session itself was extended to [millis] in the call that answered at [answered] (on [ticker]), never
     * beyond what the session has left since; answers the token of each lease key, `0` when not held. On a single
     * node this is one call that also drops the keys the session no longer holds from its lease index.
     */
    private fun extend(
        session: SessionId,
        millis: Long,
        answered: Long,
        leaseKeys: List<String>,
        groupKeys: Set<String>,
    ): Map<String, Long> {
        val left = (millis - millisSince(answered)).coerceAtLeast(0)
        val calls = if (routeBySlot) {
            routed(leaseKeys + groupKeys) { keys ->
                val slotLeases = keys.filter { it !in groupKeys }
                ScriptCall(
                    KEEP_ALIVE,
                    slotLeases + keys.filter { it in groupKeys },
                    listOf(
                        WITHOUT_SESSION,
                        session.value,
                        millis.toString(),
                        slotLeases.size.toString(),
                        left.toString()
                    )
                )
            }
        } else if (leaseKeys.isEmpty() && groupKeys.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ScriptCall(
                    KEEP_ALIVE,
                    listOf(RedisLeaseKeys.sessionLeases(session)) + leaseKeys + groupKeys,
                    listOf(WITH_SESSION, session.value, millis.toString(), leaseKeys.size.toString(), left.toString())
                )
            )
        }
        if (calls.isEmpty()) {
            return emptyMap()
        }
        val tokens = HashMap<String, Long>()
        scripting.eval(calls).replies.zip(calls).forEach { (reply, call) ->
            val slotLeases = if (routeBySlot) call.keys else call.keys.drop(1)
            reply.forEachIndexed { index, token -> tokens[slotLeases[index]] = token as Long }
        }
        return tokens
    }

    /**
     * Drops [stale] lease keys (with the scores keep-alive read) from the session's lease index on a Cluster: keys
     * the session did not hold, whose last attempt began at least one lease ago. An acquire whose lease calls could
     * still take one (a reply arriving later than the session's remaining time after its session call was sent)
     * releases what it began.
     */
    private fun forget(session: SessionId, stale: Map<String, String>) {
        if (stale.isNotEmpty()) {
            eval(
                FORGET,
                listOf(RedisLeaseKeys.sessionLeases(session)),
                stale.flatMap { (key, score) -> listOf(key, score) }
            )
        }
    }

    /** The answer of a [sessionCall]: what the session had left, its lease, and when (on [ticker]) the call was sent. */
    private inner class SessionReply(
        val session: SessionId,
        val remaining: Long,
        val lease: Long,
        val sent: Long,
    ) {
        /** How long the lease slots grant: one full session lease; fails when the session may have expired. */
        fun grantFor(what: String): Long {
            if (late()) {
                throw SimbaException("$what for session [$session] came later than the session's remaining time.")
            }
            return lease
        }

        /** Whether the session may have expired: its remaining time passed since the call was sent. */
        fun late(): Boolean = millisSince(sent) >= remaining
    }

    /**
     * Runs [script] on the session's slot only: checks the session is live, records [indexed] in [index], and answers
     * the session's remaining time and lease.
     */
    private fun sessionCall(
        script: LeaseScript,
        session: SessionId,
        index: String,
        indexed: List<String>,
    ): SessionReply {
        val sent = ticker.nanoTime()
        val reply = eval(
            script,
            listOf(RedisLeaseKeys.session(session), index),
            listOf(WITH_SESSION, session.value, "0") + indexed
        )
        if (reply.long(0) == EXPIRED) {
            throw SessionExpiredException(session)
        }
        return SessionReply(session, reply.long(0), reply.long(1), sent)
    }

    /**
     * Releases the terms [replies] began after they arrived later than the session's remaining time, and fails:
     * the session may have died, and keep-alive may have dropped their keys from its index.
     */
    private fun abandon(
        session: SessionId,
        names: Set<LeaseName>,
        replies: List<Pair<List<String>, List<Any?>>>
    ): Nothing {
        val begun = HashMap<LeaseName, Long>()
        for ((callKeys, reply) in replies) {
            callKeys.forEachIndexed { index, key ->
                val offset = ACQUIRE_HEADER + index * ACQUIRE_FIELDS
                if (reply[offset + 1] == session.value && reply.long(offset + 2) == 1L) {
                    begun[checkNotNull(RedisLeaseKeys.leaseOf(key))] = reply.long(offset)
                }
            }
        }
        try {
            release(session, begun)
        } catch (error: RuntimeException) {
            log.warn(error) { "Releasing the late leases ${begun.keys} of session [$session] failed." }
        }
        throw SimbaException(
            "Acquiring $names for session [$session] answered later than the session's remaining time."
        )
    }

    private fun millisSince(nanos: Long): Long = (ticker.nanoTime() - nanos) / NANOS_PER_MILLI

    private fun unreplicated(session: SessionId, begun: Map<LeaseName, Long>, replicas: Int): Nothing {
        val required = checkNotNull(waitReplicas).replicas
        try {
            release(session, begun)
        } catch (error: RuntimeException) {
            log.warn(error) { "Releasing the unreplicated leases ${begun.keys} of session [$session] failed." }
        }
        throw SimbaException(
            "Leases ${begun.keys} of session [$session] reached $replicas of $required replicas; released them."
        )
    }

    /** The session hash, its lease index and its group index. */
    private fun sessionKeys(session: SessionId): List<String> =
        listOf(
            RedisLeaseKeys.session(session),
            RedisLeaseKeys.sessionLeases(session),
            RedisLeaseKeys.sessionGroups(session)
        )

    /** One call per hash slot of [keys] with [routeBySlot], else one call for all; none without keys. */
    private fun routed(keys: List<String>, call: (List<String>) -> ScriptCall): List<ScriptCall> =
        when {
            keys.isEmpty() -> emptyList()
            routeBySlot -> keys.groupBy(RedisSlots::slot).values.map(call)
            else -> listOf(call(keys))
        }

    private fun eval(script: LeaseScript, keys: List<String>, args: List<String>): List<Any?> =
        scripting.eval(listOf(ScriptCall(script, keys, args))).replies.single()

    private fun List<Any?>.long(index: Int): Long = this[index] as Long
}
