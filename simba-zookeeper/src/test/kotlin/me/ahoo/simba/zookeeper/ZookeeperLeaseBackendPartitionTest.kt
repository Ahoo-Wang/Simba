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
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.test.asserts.assert
import org.apache.curator.test.InstanceSpec
import org.apache.curator.test.TestingCluster
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A session on a follower that lost the leader. The follower still answers reads from its own state (until it
 * notices, `syncLimit` ticks later) while the leader stops seeing the session and expires it. A keep-alive must not
 * succeed on such a follower: it would extend the old holder's deadline past the moment another node takes over.
 *
 * Every server reaches the others' quorum ports through a [FreezingProxy]; freezing the proxies of one follower cuts
 * it off from the leader and keeps its client port open.
 */
class ZookeeperLeaseBackendPartitionTest {
    private val proxies = HashMap<Pair<Int, Int>, FreezingProxy>()
    private lateinit var specs: List<InstanceSpec>
    private lateinit var cluster: TestingCluster

    @BeforeEach
    fun setup() {
        specs = List(SERVERS) { InstanceSpec(null, -1, -1, -1, true, it + 1, TICK_MILLIS, -1) }
        val views = specs.associateWith { self ->
            specs.map { peer ->
                if (peer == self) {
                    peer
                } else {
                    val proxy = FreezingProxy(peer.quorumPort).also { proxies[self.serverId to peer.serverId] = it }
                    InstanceSpec(
                        peer.dataDirectory,
                        peer.port,
                        peer.electionPort,
                        proxy.port,
                        false,
                        peer.serverId,
                        TICK_MILLIS,
                        -1,
                    )
                }
            }
        }
        cluster = TestingCluster(views).apply { start() }
    }

    @AfterEach
    fun destroy() {
        cluster.close()
        proxies.values.forEach(FreezingProxy::close)
    }

    @Test
    fun aFollowerCutOffFromTheLeaderNeverRenewsTheSession() {
        val (leader, follower) = awaitRoles()
        val name = LeaseName("partitioned")
        ZookeeperLeaseBackend("127.0.0.1:${follower.port}").use { isolated ->
            ZookeeperLeaseBackend("127.0.0.1:${leader.port}").use { contender ->
                val session = isolated.openSession(LEASE).id
                val token = (isolated.acquire(session, setOf(name)).getValue(name) as AcquireResult.Granted).token
                (isolated.keepAlive(session, LEASE, setOf(name)) as KeepAliveResult.Live).owned
                    .assert().isEqualTo(mapOf(name to token))

                proxies.filterKeys { it.first == follower.serverId }.values.forEach(FreezingProxy::freeze)

                // The follower would still answer a plain read for about syncLimit ticks.
                assertThrows<SimbaException> { isolated.keepAlive(session, LEASE, setOf(name)) }
                val other = contender.openSession(LEASE).id
                awaitTrue { contender.acquire(other, setOf(name)).getValue(name) is AcquireResult.Granted }
            }
        }
    }

    /** The leader and one follower, once the ensemble elected a leader. */
    private fun awaitRoles(): Pair<InstanceSpec, InstanceSpec> {
        var roles: Pair<InstanceSpec, InstanceSpec>? = null
        awaitTrue {
            val modes = specs.associateWith(::mode)
            val leader = modes.entries.singleOrNull { it.value == "leader" }?.key
            val follower = modes.entries.firstOrNull { it.value == "follower" }?.key
            if (leader != null && follower != null) {
                roles = leader to follower
            }
            roles != null
        }
        return checkNotNull(roles)
    }

    /** The server's `Mode` from the `srvr` four-letter command (enabled by default). */
    private fun mode(spec: InstanceSpec): String? = runCatching {
        Socket("127.0.0.1", spec.port).use { socket ->
            socket.getOutputStream().write("srvr".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString()
        }
    }.getOrNull()?.lineSequence()?.firstOrNull { it.startsWith("Mode: ") }?.removePrefix("Mode: ")?.trim()

    private fun awaitTrue(condition: () -> Boolean) {
        val done = CompletableFuture<Unit>()
        val poller = Executors.newSingleThreadScheduledExecutor()
        try {
            poller.scheduleWithFixedDelay({
                try {
                    if (condition()) {
                        done.complete(Unit)
                    }
                } catch (error: Throwable) {
                    done.completeExceptionally(error)
                }
            }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
            done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            poller.shutdownNow()
        }
    }

    companion object {
        private const val SERVERS = 3
        private const val TICK_MILLIS = 200
        private const val POLL_MILLIS = 50L
        private const val TIMEOUT_SECONDS = 60L

        /** The servers grant at most 20 ticks. */
        private val LEASE = Duration.ofSeconds(4)
    }
}
