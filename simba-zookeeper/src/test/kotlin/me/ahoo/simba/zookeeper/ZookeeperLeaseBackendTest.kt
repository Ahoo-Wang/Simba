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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Zookeeper-specific behavior of [ZookeeperLeaseBackend]: czxid tokens, session expiry and suspension. */
class ZookeeperLeaseBackendTest {
    private val lease = Duration.ofSeconds(30)
    private lateinit var server: TestingServer
    private lateinit var backend: ZookeeperLeaseBackend
    private lateinit var poller: ScheduledExecutorService

    @BeforeEach
    fun setup() {
        server = TestingServer()
        backend = ZookeeperLeaseBackend(server.connectString)
        poller = Executors.newSingleThreadScheduledExecutor()
    }

    @AfterEach
    fun destroy() {
        poller.shutdownNow()
        backend.close()
        server.close()
    }

    @Test
    fun theSessionIdIsTheZookeeperSessionIdAndRemainingIsBoundedByTheNegotiatedTimeout() {
        val grant = backend.openSession(lease)
        val zooKeeper = checkNotNull(backend.zooKeeperOf(grant.id))

        grant.id.value.assert().isEqualTo("%016x".format(zooKeeper.sessionId))
        grant.remaining.assert().isEqualTo(minOf(lease, Duration.ofMillis(zooKeeper.sessionTimeout.toLong())))
    }

    @Test
    fun theTokenIsTheCzxidOfTheEphemeralLeaseNode() {
        val owner = backend.openSession(lease).id
        val contender = backend.openSession(lease).id
        val name = name()

        val token = backend.granted(owner, name)

        val stat = checkNotNull(backend.zooKeeperOf(contender)).exists("/simba/leases/$name", false)
        token.assert().isEqualTo(stat.czxid)
        stat.ephemeralOwner.assert().isEqualTo(checkNotNull(backend.zooKeeperOf(owner)).sessionId)
        backend.acquire(contender, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(owner, token)))
        backend.acquire(owner, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(token)))
    }

    @Test
    fun tokensStrictlyIncreaseAcrossTermsAndNames() {
        val first = backend.openSession(lease).id
        val second = backend.openSession(lease).id
        val name = name()
        val other = name()

        val one = backend.granted(first, name)
        val unrelated = backend.granted(first, other)
        backend.release(first, mapOf(name to one))
        val two = backend.granted(second, name)
        backend.release(second, mapOf(name to two))
        val three = backend.granted(second, name)

        one.assert().isPositive()
        unrelated.assert().isGreaterThan(one)
        two.assert().isGreaterThan(unrelated)
        three.assert().isGreaterThan(two)
    }

    @Test
    fun aBatchAnswersEveryNameAndKeepAliveReportsWhatTheSessionAcquired() {
        val session = backend.openSession(lease).id
        val other = backend.openSession(lease).id
        val held = name()
        val heldToken = backend.granted(other, held)
        val free = setOf(name(), name())

        val results = backend.acquire(session, free + held)

        results.keys.assert().isEqualTo(free + held)
        results[held].assert().isEqualTo(AcquireResult.HeldBy(other, heldToken))
        val tokens = free.associateWith { (results.getValue(it) as AcquireResult.Granted).token }
        tokens.values.forEach { it.assert().isGreaterThan(heldToken) }
        (backend.keepAlive(session, lease, emptySet()) as KeepAliveResult.Live).owned.assert().isEqualTo(tokens)
    }

    @Test
    fun aSessionExpiredByZookeeperIsNeverRevivedAndItsLeasesAreTakenOver() {
        val expiring = backend.openSession(lease).id
        val contender = backend.openSession(lease).id
        val name = name()
        val token = backend.granted(expiring, name)
        val group = "group-${UUID.randomUUID()}"
        backend.join(expiring, group)
        backend.acquire(contender, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(expiring, token)))
        val hints = LinkedBlockingQueue<BackendHint>()

        backend.subscribe(hints::add).use {
            expireOnServer(checkNotNull(backend.zooKeeperOf(expiring)))
            awaitHint(hints, BackendHint.Released(name))
        }
        // The client sees the connection drop before Zookeeper tells it the session expired: suspended meanwhile.
        awaitUntil { runCatching { backend.keepAlive(expiring, lease, setOf(name)) }.getOrNull() == KeepAliveResult.Expired }

        backend.keepAlive(expiring, lease, setOf(name)).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.acquire(expiring, setOf(name)) }
        assertThrows<SessionExpiredException> { backend.join(expiring, group) }
        backend.members(group).assert().isEmpty()
        backend.granted(contender, name).assert().isGreaterThan(token)
        backend.openSession(lease).id.assert().isNotIn(expiring, contender)
    }

    @Test
    fun aSuspendedSessionFailsKeepAliveAndAcquireUntilItReconnects() {
        val session = backend.openSession(lease).id
        val name = name()
        val token = backend.granted(session, name)

        server.stop()
        awaitUntil { runCatching { backend.keepAlive(session, lease, setOf(name)) }.isFailure }

        assertThrows<SimbaException> { backend.keepAlive(session, lease, setOf(name)) }
        assertThrows<SimbaException> { backend.acquire(session, setOf(name())) }

        server.restart()
        awaitUntil { runCatching { backend.keepAlive(session, lease, setOf(name)) }.isSuccess }

        backend.keepAlive(session, lease, setOf(name)).assert()
            .isEqualTo(
                KeepAliveResult.Live(backend.keepAlive(session, lease, emptySet()).remaining(), mapOf(name to token))
            )
    }

    @Test
    fun aJoinAndAWatchedRemoteChangeAnnounceMembersChanged() {
        val group = "group-${UUID.randomUUID()}"
        val local = backend.openSession(lease).id
        val hints = LinkedBlockingQueue<BackendHint>()
        ZookeeperLeaseBackend(server.connectString).use { remote ->
            val remoteSession = remote.openSession(lease).id
            backend.subscribe(hints::add).use {
                backend.join(local, group)
                awaitHint(hints, BackendHint.MembersChanged(group))
                backend.members(group).assert().containsExactly(local)

                remote.join(remoteSession, group)

                awaitHint(hints, BackendHint.MembersChanged(group))
                backend.members(group).assert().isEqualTo(listOf(local, remoteSession).sortedBy { it.value })
            }
        }
    }

    private fun KeepAliveResult.remaining(): Duration = (this as KeepAliveResult.Live).remaining

    private fun name(): LeaseName = LeaseName("zk.${UUID.randomUUID()}")

    private fun ZookeeperLeaseBackend.granted(session: SessionId, name: LeaseName): Long {
        val result = acquire(session, setOf(name)).getValue(name)
        result.assert().isInstanceOf(AcquireResult.Granted::class.java)
        return (result as AcquireResult.Granted).token
    }

    /** Closes the session from a second client that attaches to it, so the server expires it for the first one. */
    private fun expireOnServer(zooKeeper: ZooKeeper) {
        val connected = CountDownLatch(1)
        val intruder = ZooKeeper(
            server.connectString,
            zooKeeper.sessionTimeout,
            { if (it.state == Watcher.Event.KeeperState.SyncConnected) connected.countDown() },
            zooKeeper.sessionId,
            zooKeeper.sessionPasswd,
        )
        connected.await(30, TimeUnit.SECONDS).assert().isTrue()
        intruder.close()
    }

    private fun awaitHint(hints: LinkedBlockingQueue<BackendHint>, expected: BackendHint) {
        while (true) {
            val hint = checkNotNull(hints.poll(30, TimeUnit.SECONDS)) { "No hint $expected." }
            if (hint == expected) {
                return
            }
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val done = CompletableFuture<Unit>()
        val polling = poller.scheduleWithFixedDelay({
            try {
                if (condition()) {
                    done.complete(Unit)
                }
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }, 0, 20, TimeUnit.MILLISECONDS)
        try {
            done.get(30, TimeUnit.SECONDS)
        } finally {
            polling.cancel(false)
        }
    }
}
