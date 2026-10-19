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

import me.ahoo.simba.SimbaException
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.ACL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Error paths of [ZookeeperLeaseBackend]: invalid input, broken clients, foreign changes and server refusals. */
class ZookeeperLeaseBackendFailureTest {
    private val lease = Duration.ofSeconds(30)
    private lateinit var server: TestingServer
    private lateinit var backend: ZookeeperLeaseBackend
    private lateinit var admin: ZooKeeper

    @BeforeEach
    fun setup() {
        server = TestingServer()
        backend = ZookeeperLeaseBackend(server.connectString)
        admin = connect(server.connectString)
    }

    @AfterEach
    fun destroy() {
        admin.close()
        backend.close()
        server.close()
    }

    @Test
    fun invalidArgumentsAreRejected() {
        val session = backend.openSession(lease).id

        assertThrows<IllegalArgumentException> { ZookeeperLeaseBackend(server.connectString, root = "simba") }
        assertThrows<IllegalArgumentException> { backend.openSession(Duration.ZERO) }
        assertThrows<IllegalArgumentException> { backend.acquire(session, List(65) { name() }.toSet()) }
        listOf("", "a/b", ".", "..").forEach { group ->
            assertThrows<IllegalArgumentException> { backend.members(group) }
            assertThrows<IllegalArgumentException> { backend.join(session, group) }
        }
    }

    @Test
    fun openSessionFailsWhenNoServerAnswersInTime() {
        val unreachable = TestingServer().apply { stop() }
        try {
            ZookeeperLeaseBackend(unreachable.connectString, connectionTimeout = Duration.ofMillis(200)).use {
                assertThrows<SimbaException> { it.openSession(lease) }
            }
        } finally {
            unreachable.close()
        }
    }

    @Test
    fun openSessionRestoresTheInterruptWhileWaitingForTheServer() {
        val unreachable = TestingServer().apply { stop() }
        try {
            ZookeeperLeaseBackend(unreachable.connectString).use {
                Thread.currentThread().interrupt()
                assertThrows<InterruptedException> { it.openSession(lease) }
            }
        } finally {
            Thread.interrupted()
            unreachable.close()
        }
    }

    @Test
    fun aClosedClientIsAnExpiredSession() {
        val session = backend.openSession(lease).id
        val group = group()
        checkNotNull(backend.zooKeeperOf(session)).close()

        assertThrows<SessionExpiredException> { backend.acquire(session, setOf(name())) }
        backend.zooKeeperOf(session).assert().isNull()
        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.join(session, group) }
        backend.release(session, mapOf(name() to 1L))
        backend.leave(session, group)
    }

    @Test
    fun aClientClosedBeforeJoinIsAnExpiredSession() {
        val session = backend.openSession(lease).id
        checkNotNull(backend.zooKeeperOf(session)).close()

        assertThrows<SessionExpiredException> { backend.join(session, group()) }
        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
    }

    @Test
    fun keepAliveOfAClosedClientIsExpired() {
        val session = backend.openSession(lease).id
        checkNotNull(backend.zooKeeperOf(session)).close()

        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.acquire(session, setOf(name())) }
    }

    @Test
    fun keepAliveReportsOnlyLeasesTheSessionStillOwnsAndForgetsDeletedOnes() {
        val session = backend.openSession(lease).id
        val other = backend.openSession(lease).id
        val deleted = name()
        val foreign = name()
        val kept = name()
        backend.granted(session, deleted)
        backend.granted(other, foreign)
        val keptToken = backend.granted(session, kept)
        val hints = LinkedBlockingQueue<BackendHint>()

        backend.subscribe(hints::add).use {
            admin.delete(leasePath(deleted), -1)
            awaitHint(hints, BackendHint.Released(deleted))
        }

        backend.keepAlive(session, lease, setOf(foreign)).live().owned.assert().isEqualTo(mapOf(kept to keptToken))
        backend.keepAlive(session, lease, emptySet()).live().owned.assert().isEqualTo(mapOf(kept to keptToken))
    }

    @Test
    fun aShorterKeepAliveNeverShortensTheSession() {
        val session = backend.openSession(lease).id

        backend.keepAlive(session, Duration.ofMillis(1), emptySet()).live().remaining.assert()
            .isEqualTo(Duration.ofMillis(1))

        backend.keepAlive(session, lease, emptySet()).assert().isInstanceOf(KeepAliveResult.Live::class.java)
    }

    @Test
    fun aDataChangeIsNoReleaseHintAndAFailingListenerDoesNotStopTheOthers() {
        val owner = backend.openSession(lease).id
        val name = name()
        val token = backend.granted(owner, name)
        val hints = LinkedBlockingQueue<BackendHint>()

        backend.subscribe { error("listener failure") }.use {
            backend.subscribe(hints::add).use {
                admin.setData(leasePath(name), ByteArray(0), -1)
                // The data change consumed the watch; acquiring again re-arms it.
                backend.acquire(owner, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(token)))
                backend.release(owner, mapOf(name to token))

                checkNotNull(hints.poll(30, TimeUnit.SECONDS)).assert().isEqualTo(BackendHint.Released(name))
            }
        }
    }

    @Test
    fun serverRefusalsSurfaceAsKeeperExceptions() {
        val session = backend.openSession(lease).id
        val owned = name()
        val token = backend.granted(session, owned)
        val readOnly =
            ACL(ZooDefs.Perms.READ or ZooDefs.Perms.WRITE or ZooDefs.Perms.ADMIN, ZooDefs.Ids.ANYONE_ID_UNSAFE)
        admin.setACL("/simba/leases", listOf(readOnly), -1)

        assertThrows<KeeperException.NoAuthException> { backend.acquire(session, setOf(name())) }
        assertThrows<KeeperException.NoAuthException> { backend.release(session, mapOf(owned to token)) }
        backend.keepAlive(session, lease, setOf(owned)).live().owned.assert().isEqualTo(mapOf(owned to token))
    }

    @Test
    fun releaseAndLeaveOfAnUnknownSessionAreNoOps() {
        val unknown = SessionId.random()

        backend.release(unknown, mapOf(name() to 1L))
        backend.leave(unknown, group())
        backend.closeSession(unknown)
        backend.zooKeeperOf(unknown).assert().isNull()
    }

    @Test
    fun closingASessionOnAnInterruptedThreadKeepsTheInterrupt() {
        val session = backend.openSession(lease).id
        val name = name()
        backend.granted(session, name)
        try {
            Thread.currentThread().interrupt()
            backend.closeSession(session)
            Thread.currentThread().isInterrupted.assert().isTrue()
        } finally {
            Thread.interrupted()
        }
        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        backend.acquire(backend.openSession(lease).id, setOf(name)).values.single().assert()
            .isInstanceOf(AcquireResult.Granted::class.java)
    }

    private fun name(): LeaseName = LeaseName("zk.${UUID.randomUUID()}")

    private fun group(): String = "group-${UUID.randomUUID()}"

    private fun leasePath(name: LeaseName): String = "/simba/leases/$name"

    private fun ZookeeperLeaseBackend.granted(session: SessionId, name: LeaseName): Long {
        val result = acquire(session, setOf(name)).getValue(name)
        result.assert().isInstanceOf(AcquireResult.Granted::class.java)
        return (result as AcquireResult.Granted).token
    }

    private fun KeepAliveResult.live(): KeepAliveResult.Live {
        assert().isInstanceOf(KeepAliveResult.Live::class.java)
        return this as KeepAliveResult.Live
    }

    private fun awaitHint(hints: LinkedBlockingQueue<BackendHint>, expected: BackendHint) {
        while (true) {
            val hint = checkNotNull(hints.poll(30, TimeUnit.SECONDS)) { "No hint $expected." }
            if (hint == expected) {
                return
            }
        }
    }

    private fun connect(connectString: String): ZooKeeper {
        val connected = CountDownLatch(1)
        val zooKeeper = ZooKeeper(connectString, 30_000) {
            if (it.state == Watcher.Event.KeeperState.SyncConnected) connected.countDown()
        }
        connected.await(30, TimeUnit.SECONDS).assert().isTrue()
        return zooKeeper
    }
}
