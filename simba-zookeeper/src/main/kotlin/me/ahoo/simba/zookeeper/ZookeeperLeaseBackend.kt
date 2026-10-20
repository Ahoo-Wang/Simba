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

package me.ahoo.simba.zookeeper

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
import org.apache.curator.framework.AuthInfo
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.api.BackgroundCallback
import org.apache.curator.framework.imps.DefaultACLProvider
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.AsyncCallback
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.client.ZKClientConfig
import org.apache.zookeeper.data.ACL
import org.apache.zookeeper.data.Stat
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * [LeaseBackend] on Zookeeper ephemeral nodes (ADR 0010 §7.3).
 *
 * A session is one Zookeeper session: [openSession] connects a dedicated [ZooKeeper] client and the [SessionId] is
 * its session id (16 lowercase hex digits). A client is never reconnected to another session, so an expired session
 * stays expired and the next [openSession] gets a new id.
 *
 * - A lease is the ephemeral node `{root}/leases/{name}` holding the session id; its token is the node's czxid, which
 *   is strictly increasing across terms (Zookeeper zxids are global) but not consecutive. The owner is read from
 *   the node's `ephemeralOwner`, so a lease belongs to its session exactly while Zookeeper keeps the session.
 * - A member is the ephemeral node `{root}/groups/{group}/{sessionId}` under a container node.
 * - Hints: every acquire leaves an exists watch on the lease node, whose deletion is announced as
 *   [BackendHint.Released]; [members] leaves a children watch (an exists watch while the group node is missing), and
 *   [join] / [leave] announce their own change.
 * - A name is recorded as possibly owned before its create is sent, so [keepAlive] reports (after checking the node's
 *   owner) a lease whose create succeeded although its reply was lost. Releases of one session run one at a time, so
 *   the owner and czxid check before a delete cannot be overtaken by another release of the same session.
 *
 * The Zookeeper client heartbeats on its own, so a session would otherwise outlive a caller that stopped keeping it
 * alive. A local watchdog therefore closes a session (deleting its nodes) once no keep-alive started within the last
 * `lease`. While the client is disconnected
 * (Curator's `SUSPENDED`), [keepAlive] and [acquire] fail, so the caller's own deadline runs out; when Zookeeper
 * reports the session expired (`LOST`), the session answers [KeepAliveResult.Expired].
 *
 * Only the leader expires sessions, and a follower answers reads on its own (until it notices it lost the leader,
 * `syncLimit` ticks later) while it reports the session's activity to the leader only on its next ping. So a
 * keep-alive first `sync`s through the leader, and a read that succeeded proves the session alive at the leader only
 * up to that bound: the remaining duration is `min(lease, 5/12 × negotiated session timeout)`. The client sends at
 * least every third of the timeout and the leader pings every half tick (at most a quarter of the timeout, which is
 * at least two ticks), so the leader last saw activity of the session at most `7/12` of the timeout before the sync
 * reply, which came after the keep-alive was sent.
 *
 * Security: every client (each session's and the reader's) authenticates with [authInfo] and is built with
 * [clientConfig]; every node the backend creates (leases, members, and the parents it creates) gets the ACL of
 * [aclProvider]. With the default open ACL any client of the ensemble can delete or pre-create lease nodes and forge
 * members: in a shared ensemble, authenticate (e.g. `digest`) and use an ACL such as [ZooDefs.Ids.CREATOR_ALL_ACL].
 * Parents that already exist keep their ACL; the ACL of `{root}/leases` and `{root}/groups/{group}` decides who may
 * create or delete their children.
 *
 * [members] reads through the client of the session that last joined or left the group here, so that change is
 * always in the view (Zookeeper orders the requests of one session); without one, through a separate reader client.
 * It `sync`s the group path first, which brings a follower up to date with the leader. `sync` is no barrier on the
 * leader itself, which answers it locally: there a change made through another client can show a moment later.
 *
 * @param connectString Zookeeper connect string, shared by every session client and the client [members] reads with.
 * @param root parent path of the lease and group nodes.
 * @param connectionTimeout how long [openSession] waits for a new session. Keep it at most the caller's call timeout
 *   (the engine's renew interval): a session that connects after the caller gave up has no keep-alive, so the
 *   watchdog closes it only after its lease.
 * @param authInfo authentication (scheme and credentials) every client adds before its first request.
 * @param aclProvider ACL of each node the backend creates, by path.
 * @param clientConfig configuration of every client (TLS, SASL, ...); `null` for the defaults.
 */
class ZookeeperLeaseBackend @JvmOverloads constructor(
    private val connectString: String,
    root: String = DEFAULT_ROOT,
    val connectionTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECTION_TIMEOUT_SECONDS),
    authInfo: List<AuthInfo> = emptyList(),
    private val aclProvider: ACLProvider = DefaultACLProvider(),
    private val clientConfig: ZKClientConfig? = null,
) : LeaseBackend {
    companion object {
        private val log = KotlinLogging.logger {}
        const val DEFAULT_ROOT = "/simba"
        private const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 15L
        private const val MAX_ATTEMPTS = 8
        private const val RETRY_BASE_MILLIS = 100
        private const val RETRY_COUNT = 3
        private val EMPTY = ByteArray(0)

        /** Remaining share of the negotiated session timeout a leader-confirmed keep-alive may report. */
        private const val REMAINING_TWELFTHS = 5L
        private const val TWELFTHS = 12L

        internal fun sessionIdOf(zookeeperSessionId: Long): SessionId = SessionId("%016x".format(zookeeperSessionId))
    }

    private val authInfo = authInfo.toList()
    private val root = root.trimEnd('/')
    private val leasesPath = "${this.root}/leases"
    private val groupsPath = "${this.root}/groups"
    private val sessions = ConcurrentHashMap<SessionId, Session>()
    private val listeners = CopyOnWriteArrayList<BackendHintListener>()

    /** Per group, the session that last joined or left it here: [members] reads through its client. */
    private val groupWriters = ConcurrentHashMap<String, Session>()
    private val epochs = AtomicLong()

    @Volatile
    private var closed = false
    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "simba-zookeeper-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val reader: CuratorFramework = CuratorFrameworkFactory.builder()
        .connectString(connectString)
        .connectionTimeoutMs(connectionTimeout.toMillis().toInt())
        .retryPolicy(ExponentialBackoffRetry(RETRY_BASE_MILLIS, RETRY_COUNT))
        .authorization(authInfo)
        .aclProvider(aclProvider)
        .apply { clientConfig?.let(::zkClientConfig) }
        .build()
        .apply { start() }

    private val leaseWatcher = Watcher { event ->
        if (event.type == Watcher.Event.EventType.NodeDeleted) {
            publish(BackendHint.Released(LeaseName(event.path.substringAfterLast('/'))))
        }
    }
    private val groupWatcher = Watcher { event ->
        if (event.type == Watcher.Event.EventType.NodeChildrenChanged ||
            event.type == Watcher.Event.EventType.NodeCreated ||
            event.type == Watcher.Event.EventType.NodeDeleted
        ) {
            publish(BackendHint.MembersChanged(event.path.substringAfterLast('/')))
        }
    }

    init {
        require(this.root.startsWith('/')) { "Root [$root] must be an absolute path." }
    }

    override val capabilities: Capabilities = Capabilities(releaseHints = true, membershipHints = true)

    /**
     * Connects a new Zookeeper session; [metadata] is not stored (Zookeeper sessions carry none). Fails once the
     * backend is closed, closing the client it may have opened meanwhile.
     */
    override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant {
        LeaseBackend.requireLease(lease)
        ensureOpen()
        val startedAt = System.nanoTime()
        val watcher = SessionWatcher()
        val timeout = lease.toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val zooKeeper = if (clientConfig == null) {
            ZooKeeper(connectString, timeout, watcher)
        } else {
            ZooKeeper(connectString, timeout, watcher, clientConfig)
        }
        // Queued before any request and resent on every reconnect.
        authInfo.forEach { zooKeeper.addAuthInfo(it.scheme, it.auth) }
        val connected = try {
            watcher.connected.await(connectionTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            zooKeeper.close()
            throw error
        }
        if (!connected || !zooKeeper.state.isConnected) {
            zooKeeper.close()
            throw SimbaException("No Zookeeper session within $connectionTimeout.")
        }
        val session = Session(sessionIdOf(zooKeeper.sessionId), zooKeeper)
        sessions[session.id] = session
        try {
            // close() sets the flag before closing the registered sessions: one of both sees the other.
            ensureOpen()
            watcher.attach(session)
            session.extend(startedAt, lease)
        } catch (error: Throwable) {
            session.expire()
            throw error
        }
        log.debug { "Opened Zookeeper session [${session.id}], timeout ${zooKeeper.sessionTimeout} ms." }
        return SessionGrant(session.id, session.remaining(lease))
    }

    override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult {
        LeaseBackend.requireLease(lease)
        val live = sessions[session] ?: return KeepAliveResult.Expired
        val startedAt = System.nanoTime()
        return live.expiringOn {
            live.ensureConnected()
            val heldPaths = held.associateWith(::leasePath)
            val (candidates, synced, pending) = live.sendLocked {
                val candidates = HashMap(live.candidates)
                val paths = heldPaths + candidates.keys.associateWith(::leasePath)
                // The sync goes through the leader, and the reads queue behind it: a follower that lost the leader
                // cannot answer this keep-alive from its own state.
                val synced = live.zooKeeper.syncAsync("/")
                Triple(candidates, synced, paths.mapValues { live.zooKeeper.existsAsync(it.value, null) })
            }
            val code = synced.await()
            if (code != KeeperException.Code.OK) {
                throw KeeperException.create(code, "/")
            }
            val stats = pending.mapValues { it.value.await() }
            if (!live.extend(startedAt, lease)) {
                return KeepAliveResult.Expired
            }
            val owned = stats.mapNotNull { (name, reply) ->
                reply.stat?.takeIf { live.owns(it) }?.let { name to it.czxid }
            }.toMap()
            candidates.forEach { (name, epoch) ->
                if (name !in owned) {
                    live.candidates.remove(name, epoch)
                }
            }
            KeepAliveResult.Live(live.remaining(lease), owned)
        } ?: KeepAliveResult.Expired
    }

    override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> {
        LeaseBackend.requireBatch(names)
        val live = sessions[session] ?: throw SessionExpiredException(session)
        return live.expiringOn {
            live.ensureConnected()
            val results = HashMap<LeaseName, AcquireResult>()
            var pending: Set<LeaseName> = names
            repeat(MAX_ATTEMPTS) {
                if (pending.isEmpty()) {
                    return@expiringOn results
                }
                pending = live.tryAcquire(pending, results)
            }
            if (pending.isNotEmpty()) {
                throw SimbaException("Leases $pending kept changing hands while acquiring.")
            }
            results
        } ?: throw SessionExpiredException(session)
    }

    /**
     * Deletes each lease node [session] owns with czxid equal to the token. Releases of a session are serialized, as
     * only its own client deletes its nodes: the check cannot be overtaken by another of its releases.
     */
    override fun release(session: SessionId, leases: Map<LeaseName, Long>) {
        val paths = leases.keys.associateWith(::leasePath)
        val live = sessions[session] ?: return
        live.expiringOn {
            live.ensureConnected()
            synchronized(live.releaseLock) { live.release(leases, paths) }
        }
    }

    /** Closes the Zookeeper session, which deletes all its nodes at once. */
    override fun closeSession(session: SessionId) {
        sessions[session]?.expire()
    }

    override fun join(session: SessionId, group: String) {
        val path = memberPath(group, session)
        val live = sessions[session] ?: throw SessionExpiredException(session)
        live.expiringOn {
            live.ensureConnected()
            repeat(MAX_ATTEMPTS) {
                try {
                    live.zooKeeper.create(path, EMPTY, aclFor(path), CreateMode.EPHEMERAL)
                    groupWriters[group] = live
                    publish(BackendHint.MembersChanged(group))
                    return@expiringOn
                } catch (_: KeeperException.NodeExistsException) {
                    groupWriters[group] = live
                    return@expiringOn
                } catch (_: KeeperException.NoNodeException) {
                    // The group container is missing or was just reaped.
                    live.zooKeeper.ensure(groupsPath, CreateMode.PERSISTENT, ::aclFor)
                    live.zooKeeper.ensure(groupPath(group), CreateMode.CONTAINER, ::aclFor)
                }
            }
            throw SimbaException("Group [$group] kept disappearing while joining.")
        } ?: throw SessionExpiredException(session)
    }

    override fun leave(session: SessionId, group: String) {
        val path = memberPath(group, session)
        val live = sessions[session] ?: return
        live.expiringOn {
            live.ensureConnected()
            try {
                live.zooKeeper.delete(path, -1)
                publish(BackendHint.MembersChanged(group))
            } catch (_: KeeperException.NoNodeException) {
                // Not a member.
            }
            groupWriters[group] = live
        }
    }

    override fun members(group: String): List<SessionId> {
        val path = groupPath(group)
        groupWriters[group]?.takeIf(Session::isConnected)?.let { writer ->
            writer.expiringOn { writer.members(group, path) }?.let { return it }
        }
        sync(path)
        repeat(MAX_ATTEMPTS) {
            try {
                return reader.children.usingWatcher(groupWatcher).forPath(path).map(::SessionId).sortedBy { it.value }
            } catch (_: KeeperException.NoNodeException) {
                // No children watch without the node: watch for its creation instead, unless it was just created.
                if (reader.checkExists().usingWatcher(groupWatcher).forPath(path) == null) {
                    return emptyList()
                }
            }
        }
        throw SimbaException("Group [$group] kept appearing and disappearing while reading its members.")
    }

    override fun subscribe(listener: BackendHintListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Closes every open session and the reader client; later [openSession] calls fail. */
    override fun close() {
        closed = true
        try {
            sessions.values.forEach(Session::expire)
        } finally {
            watchdog.shutdownNow()
            reader.close()
        }
    }

    /** The Zookeeper client behind [session], for tests that break it. */
    internal fun zooKeeperOf(session: SessionId): ZooKeeper? = sessions[session]?.zooKeeper

    /** Lets the reader's server catch up with the leader, so it reads every write acknowledged before. */
    private fun sync(path: String) {
        val synced = CompletableFuture<Int>()
        reader.sync().inBackground(BackgroundCallback { _, event -> synced.complete(event.resultCode) }).forPath(path)
        val code = try {
            synced.get(connectionTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw SimbaException("Zookeeper sync of [$path] timed out after $connectionTimeout.", error)
        }
        if (code != KeeperException.Code.OK.intValue() && code != KeeperException.Code.NONODE.intValue()) {
            throw KeeperException.create(KeeperException.Code.get(code), path)
        }
    }

    private fun aclFor(path: String): List<ACL> = aclProvider.getAclForPath(path) ?: aclProvider.defaultAcl

    private fun ensureOpen() {
        check(!closed) { "ZookeeperLeaseBackend is closed." }
    }

    /** [LeaseName] already excludes `/`, `.` and `..`, so every name is one valid node name. */
    private fun leasePath(name: LeaseName): String = "$leasesPath/${name.value}"

    /** Group names follow the lease name rules, so each is one valid node name. */
    private fun groupPath(group: String): String = "$groupsPath/${LeaseBackend.requireGroup(group)}"

    private fun memberPath(group: String, session: SessionId): String = "${groupPath(group)}/${session.value}"

    private fun publish(hint: BackendHint) {
        listeners.forEach { listener ->
            try {
                listener.onHint(hint)
            } catch (error: Throwable) {
                log.warn(error) { "BackendHintListener failed on $hint." }
            }
        }
    }

    /** Routes the client's connection events to its [Session], which exists only once the client connected. */
    private inner class SessionWatcher : Watcher {
        val connected = CountDownLatch(1)

        @Volatile
        private var session: Session? = null

        @Volatile
        private var expired = false

        fun attach(session: Session) {
            this.session = session
            if (expired) {
                watchdog.execute(session::expire)
            }
        }

        override fun process(event: WatchedEvent) {
            when (event.state) {
                Watcher.Event.KeeperState.SyncConnected -> connected.countDown()
                Watcher.Event.KeeperState.Expired -> {
                    expired = true
                    // Never close a client from its own event thread.
                    session?.let { watchdog.execute(it::expire) }
                }

                else -> Unit
            }
        }
    }

    private inner class Session(val id: SessionId, val zooKeeper: ZooKeeper) {
        private val ownerId = zooKeeper.sessionId

        /**
         * Names this session may own, each with the epoch of its latest acquire. A name is added before its create is
         * sent and removed only by a keep-alive or release whose reads were sent after that create (under [sendLock])
         * and found the node not owned or deleted it.
         */
        val candidates = ConcurrentHashMap<LeaseName, Long>()
        private val sendLock = Any()
        val releaseLock = Any()
        private var deadline = 0L
        private var check: ScheduledFuture<*>? = null

        @Volatile
        private var expired = false

        fun owns(stat: Stat): Boolean = stat.ephemeralOwner == ownerId

        /** See the class KDoc: the share of the session timeout a leader-confirmed reply leaves for certain. */
        fun remaining(lease: Duration): Duration =
            minOf(lease, Duration.ofMillis(zooKeeper.sessionTimeout * REMAINING_TWELFTHS / TWELFTHS))

        fun isConnected(): Boolean = !expired && zooKeeper.state == ZooKeeper.States.CONNECTED

        /** The members of [group] read after a `sync`, with a children (or, while missing, exists) watch. */
        fun members(group: String, path: String): List<SessionId> {
            val code = zooKeeper.syncAsync(path).await()
            if (code != KeeperException.Code.OK && code != KeeperException.Code.NONODE) {
                throw KeeperException.create(code, path)
            }
            repeat(MAX_ATTEMPTS) {
                try {
                    return zooKeeper.getChildren(path, groupWatcher).map(::SessionId).sortedBy { it.value }
                } catch (_: KeeperException.NoNodeException) {
                    if (zooKeeper.exists(path, groupWatcher) == null) {
                        return emptyList()
                    }
                }
            }
            throw SimbaException("Group [$group] kept appearing and disappearing while reading its members.")
        }

        fun ensureConnected() {
            val state = zooKeeper.state
            if (!state.isAlive) {
                throw KeeperException.SessionExpiredException()
            }
            if (state != ZooKeeper.States.CONNECTED) {
                throw SimbaException("Zookeeper session [$id] is suspended (client $state).")
            }
        }

        /** Moves the watchdog deadline to [startedAt] + [lease]; `false` when the session already expired. */
        @Synchronized
        fun extend(startedAt: Long, lease: Duration): Boolean {
            if (expired) {
                return false
            }
            val next = startedAt + lease.toNanos()
            if (check == null || next - deadline > 0) {
                deadline = next
                check?.cancel(false)
                check = schedule()
            }
            return true
        }

        private fun schedule(): ScheduledFuture<*> =
            watchdog.schedule(::checkDeadline, deadline - System.nanoTime(), TimeUnit.NANOSECONDS)

        private fun checkDeadline() {
            synchronized(this) {
                if (expired) {
                    return
                }
                if (System.nanoTime() - deadline < 0) {
                    check = schedule()
                    return
                }
            }
            log.info { "Zookeeper session [$id] was not kept alive in time; closing it." }
            expire()
        }

        /** Ends the session for good: forgets it and closes its client, which deletes its nodes. */
        fun expire() {
            synchronized(this) {
                if (expired) {
                    return
                }
                expired = true
                check?.cancel(false)
            }
            sessions.remove(id, this)
            groupWriters.values.removeIf { it === this }
            // ZooKeeper.close() swallows a pending interrupt: close uninterrupted and restore the flag afterwards.
            var interrupted = Thread.interrupted()
            try {
                zooKeeper.close()
            } catch (error: InterruptedException) {
                interrupted = true
                log.warn(error) { "Interrupted while closing Zookeeper session [$id]." }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        /** Runs [send], which only queues requests, so no request of another call slips in between. */
        fun <T> sendLocked(send: () -> T): T = synchronized(sendLock, send)

        /** Releases under [releaseLock]: read each node, then delete those this session owns with the given token. */
        fun release(leases: Map<LeaseName, Long>, paths: Map<LeaseName, String>) {
            val (snapshot, stats) = sendLocked {
                HashMap(candidates) to paths.mapValues { zooKeeper.existsAsync(it.value, null) }
            }
            val deletes = stats.mapNotNull { (name, pending) ->
                val stat = pending.await().stat ?: return@mapNotNull null
                if (!owns(stat) || stat.czxid != leases.getValue(name)) {
                    return@mapNotNull null
                }
                name to zooKeeper.deleteAsync(paths.getValue(name), stat.version)
            }
            deletes.forEach { (name, pending) ->
                val rc = pending.await()
                if (rc != KeeperException.Code.OK && rc != KeeperException.Code.NONODE &&
                    rc != KeeperException.Code.BADVERSION
                ) {
                    throw KeeperException.create(rc, paths.getValue(name))
                }
                snapshot[name]?.let { candidates.remove(name, it) }
            }
        }

        /**
         * Runs [action]; answers `null` (after expiring this session) when Zookeeper reports the session expired, and
         * reports a lost connection as a suspended session. A connection lost because the client is closing (the
         * watchdog or [closeSession] expired it) is an expiry too: Zookeeper fails pending and new requests with
         * `CONNECTIONLOSS` while closing, before the client state turns `CLOSED`.
         */
        inline fun <T> expiringOn(action: () -> T): T? {
            if (expired) {
                return null
            }
            return try {
                action()
            } catch (_: KeeperException.SessionExpiredException) {
                expire()
                null
            } catch (error: KeeperException.ConnectionLossException) {
                if (expired || !zooKeeper.state.isAlive) {
                    expire()
                    return null
                }
                throw SimbaException("Zookeeper session [$id] is suspended (connection lost).", error)
            }
        }

        /**
         * One pipelined round trip per pending name: create the ephemeral node, then read it back with an exists
         * watch. Answers the names to retry (the node vanished in between, or its parent is missing).
         */
        fun tryAcquire(names: Set<LeaseName>, results: MutableMap<LeaseName, AcquireResult>): Set<LeaseName> {
            val data = id.value.toByteArray()
            val epoch = epochs.incrementAndGet()
            val replies = sendLocked {
                names.associateWith { name ->
                    val path = leasePath(name)
                    candidates[name] = epoch
                    zooKeeper.createAsync(path, data, aclFor(path)) to zooKeeper.existsAsync(path, leaseWatcher)
                }
            }
            val retry = HashSet<LeaseName>()
            var missingParent = false
            replies.forEach { (name, pending) ->
                val created = pending.first.await()
                val stat = pending.second.await().stat
                when {
                    created == KeeperException.Code.NONODE -> {
                        missingParent = true
                        retry += name
                    }

                    created != KeeperException.Code.OK && created != KeeperException.Code.NODEEXISTS ->
                        throw KeeperException.create(created, leasePath(name))

                    stat == null -> retry += name
                    owns(stat) -> results[name] = AcquireResult.Granted(stat.czxid)
                    else -> {
                        candidates.remove(name, epoch)
                        results[name] = AcquireResult.HeldBy(sessionIdOf(stat.ephemeralOwner), stat.czxid)
                    }
                }
            }
            if (missingParent) {
                zooKeeper.ensure(leasesPath, CreateMode.PERSISTENT, ::aclFor)
            }
            return retry
        }
    }
}

private class StatReply(val code: KeeperException.Code, val stat: Stat?)

private fun ZooKeeper.existsAsync(path: String, watcher: Watcher?): CompletableFuture<StatReply> {
    val reply = CompletableFuture<StatReply>()
    val callback = AsyncCallback.StatCallback { rc, _, _, stat ->
        reply.complete(StatReply(KeeperException.Code.get(rc), stat))
    }
    exists(path, watcher, callback, null)
    return reply.thenApply {
        if (it.code != KeeperException.Code.OK && it.code != KeeperException.Code.NONODE) {
            throw KeeperException.create(it.code, path)
        }
        it
    }
}

private fun ZooKeeper.createAsync(path: String, data: ByteArray, acl: List<ACL>): CompletableFuture<KeeperException.Code> {
    val reply = CompletableFuture<KeeperException.Code>()
    val callback = AsyncCallback.StringCallback { rc, _, _, _ -> reply.complete(KeeperException.Code.get(rc)) }
    create(path, data, acl, CreateMode.EPHEMERAL, callback, null)
    return reply
}

private fun ZooKeeper.syncAsync(path: String): CompletableFuture<KeeperException.Code> {
    val reply = CompletableFuture<KeeperException.Code>()
    val callback = AsyncCallback.VoidCallback { rc, _, _ -> reply.complete(KeeperException.Code.get(rc)) }
    sync(path, callback, null)
    return reply
}

private fun ZooKeeper.deleteAsync(path: String, version: Int): CompletableFuture<KeeperException.Code> {
    val reply = CompletableFuture<KeeperException.Code>()
    val callback = AsyncCallback.VoidCallback { rc, _, _ -> reply.complete(KeeperException.Code.get(rc)) }
    delete(path, version, callback, null)
    return reply
}

/** Waits for an async reply, rethrowing the [KeeperException] it failed with. */
private fun <T> CompletableFuture<T>.await(): T = try {
    get()
} catch (error: ExecutionException) {
    throw error.cause ?: error
}

/**
 * Creates [path] with [mode], and its missing ancestors as persistent nodes, each with the ACL [aclFor] its path.
 * Zookeeper checks the parent's CREATE permission before it reports an existing node, so an existing ancestor under a
 * parent the backend may not create in answers `NoAuth`: such a node is kept as it is.
 */
private fun ZooKeeper.ensure(path: String, mode: CreateMode, aclFor: (String) -> List<ACL>) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    segments.indices.forEach { index ->
        val prefix = segments.take(index + 1).joinToString("/", prefix = "/")
        try {
            create(
                prefix,
                ByteArray(0),
                aclFor(prefix),
                if (index == segments.lastIndex) mode else CreateMode.PERSISTENT
            )
        } catch (_: KeeperException.NodeExistsException) {
            // Created concurrently or earlier.
        } catch (error: KeeperException.NoAuthException) {
            if (exists(prefix, false) == null) {
                throw error
            }
        }
    }
}
