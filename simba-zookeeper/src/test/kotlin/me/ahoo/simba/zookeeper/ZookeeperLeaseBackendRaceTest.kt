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

import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooKeeper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Calls that fail half-way or race each other on [ZookeeperLeaseBackend]. */
class ZookeeperLeaseBackendRaceTest {
    private val lease = Duration.ofSeconds(30)
    private lateinit var server: TestingServer
    private lateinit var backend: ZookeeperLeaseBackend
    private lateinit var admin: ZooKeeper

    @BeforeEach
    fun setup() {
        server = TestingServer()
        backend = ZookeeperLeaseBackend(server.connectString)
        admin = connect()
    }

    @AfterEach
    fun destroy() {
        admin.close()
        backend.close()
        server.close()
    }

    @Test
    fun keepAliveReportsLeasesWhoseAcquireRepliesWereLost() {
        val session = backend.openSession(lease).id
        backend.granted(session, name()) // creates the leases parent
        val names = setOf(name(), name())

        // The creates are queued before the first reply is awaited, which then fails on the interrupt.
        Thread.currentThread().interrupt()
        try {
            assertThrows<InterruptedException> { backend.acquire(session, names) }
        } finally {
            Thread.interrupted()
        }

        val owned = (backend.keepAlive(session, lease, emptySet()) as KeepAliveResult.Live).owned
        owned.keys.assert().containsAll(names)
        names.forEach { owned.getValue(it).assert().isEqualTo(admin.exists(leasePath(it), false).czxid) }
    }

    @Test
    fun aLeaseHeldByAnotherSessionIsNoLongerReported() {
        val session = backend.openSession(lease).id
        val other = backend.openSession(lease).id
        val name = name()
        val token = backend.granted(other, name)

        backend.acquire(session, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(other, token)))

        (backend.keepAlive(session, lease, emptySet()) as KeepAliveResult.Live).owned.assert().isEmpty()
    }

    @Test
    fun invalidNodeNamesCannotReachTheBackend() {
        // "." and ".." are not valid Zookeeper node names; LeaseName rejects them before any request can be built.
        listOf(".", "..").forEach { invalid -> assertThrows<IllegalArgumentException> { LeaseName(invalid) } }
    }

    @Test
    fun concurrentReleasesOfOneSessionNeverDeleteALaterOwnersNode() {
        val owner = backend.openSession(lease).id
        val contender = backend.openSession(lease).id
        val name = name()
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(ROUNDS) {
                val token = backend.granted(owner, name)
                val start = CountDownLatch(1)
                val releases = List(2) {
                    CompletableFuture.runAsync({
                        start.await()
                        backend.release(owner, mapOf(name to token))
                    }, pool)
                }
                val takeover = CompletableFuture.supplyAsync({
                    start.await()
                    var result: AcquireResult
                    do {
                        result = backend.acquire(contender, setOf(name)).getValue(name)
                    } while (result !is AcquireResult.Granted)
                    result.token
                }, pool)
                start.countDown()
                releases.forEach { it.get(30, TimeUnit.SECONDS) }
                val contenderToken = takeover.get(30, TimeUnit.SECONDS)

                backend.acquire(owner, setOf(name)).assert()
                    .isEqualTo(mapOf(name to AcquireResult.HeldBy(contender, contenderToken)))
                backend.release(contender, mapOf(name to contenderToken))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun callsRacingASessionCloseSeeAnExpiredSessionNeverASuspendedOne() {
        val name = name()
        val group = "group-${UUID.randomUUID()}"
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(ROUNDS) {
                val session = backend.openSession(lease).id
                val start = CountDownLatch(1)
                val calls = listOf<() -> Any>(
                    { backend.join(session, group) },
                    { backend.acquire(session, setOf(name)) },
                    { backend.keepAlive(session, lease, setOf(name)) },
                ).map { call ->
                    CompletableFuture.supplyAsync({
                        start.await()
                        // Until the close lands every call succeeds; afterwards each must report expiry.
                        while (backend.zooKeeperOf(session) != null) {
                            runCatching(call).exceptionOrNull()?.let { if (it !is SessionExpiredException) throw it }
                        }
                        runCatching(call).exceptionOrNull()?.let { if (it !is SessionExpiredException) throw it }
                    }, pool)
                }
                start.countDown()
                backend.closeSession(session)
                calls.forEach { it.get(30, TimeUnit.SECONDS) }
                backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun membersOfAMissingGroupWatchesForItsCreation() {
        val group = "group-${UUID.randomUUID()}"
        val hints = LinkedBlockingQueue<BackendHint>()
        backend.members(group).assert().isEmpty()

        ZookeeperLeaseBackend(server.connectString).use { remote ->
            backend.subscribe(hints::add).use {
                remote.join(remote.openSession(lease).id, group)

                checkNotNull(hints.poll(30, TimeUnit.SECONDS)).assert().isEqualTo(BackendHint.MembersChanged(group))
            }
        }
    }

    @Test
    fun aClosedBackendOpensNoSession() {
        backend.close()

        assertThrows<IllegalStateException> { backend.openSession(lease) }
    }

    @Test
    fun anOpenSessionRacingCloseClosesItsClient() {
        val stopped = TestingServer()
        val racing = ZookeeperLeaseBackend(stopped.connectString)
        val pool = Executors.newSingleThreadExecutor()
        try {
            stopped.stop()
            val opening = CompletableFuture.supplyAsync({ racing.openSession(lease) }, pool)
            awaitSendThreads(stopped.port) { it >= 2 }

            racing.close()
            stopped.restart()

            val error = assertThrows<ExecutionException> { opening.get(30, TimeUnit.SECONDS) }
            error.cause.assert().isInstanceOf(IllegalStateException::class.java)
            awaitSendThreads(stopped.port) { it == 0 }
        } finally {
            pool.shutdownNow()
            racing.close()
            stopped.close()
        }
    }

    private fun sendThreads(port: Int): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && "SendThread" in it.name && ":$port" in it.name }

    /** Waits until the number of live client send threads to [port] satisfies [condition]. */
    private fun awaitSendThreads(port: Int, condition: (Int) -> Boolean) {
        val done = CompletableFuture<Unit>()
        val poller = Executors.newSingleThreadScheduledExecutor()
        try {
            poller.scheduleWithFixedDelay({
                if (condition(sendThreads(port).size)) {
                    done.complete(Unit)
                }
            }, 0, 20, TimeUnit.MILLISECONDS)
            done.get(30, TimeUnit.SECONDS)
        } finally {
            poller.shutdownNow()
        }
    }

    private fun name(): LeaseName = LeaseName("zk.${UUID.randomUUID()}")

    private fun leasePath(name: LeaseName): String = "/simba/leases/$name"

    private fun ZookeeperLeaseBackend.granted(session: SessionId, name: LeaseName): Long {
        val result = acquire(session, setOf(name)).getValue(name)
        result.assert().isInstanceOf(AcquireResult.Granted::class.java)
        return (result as AcquireResult.Granted).token
    }

    private fun connect(): ZooKeeper {
        val connected = CountDownLatch(1)
        val zooKeeper = ZooKeeper(server.connectString, 30_000) {
            if (it.state == Watcher.Event.KeeperState.SyncConnected) connected.countDown()
        }
        connected.await(30, TimeUnit.SECONDS).assert().isTrue()
        return zooKeeper
    }

    companion object {
        private const val ROUNDS = 30
    }
}
