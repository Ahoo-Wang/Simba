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
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.test.asserts.assert
import org.apache.curator.test.InstanceSpec
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.ZooKeeper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A request still in flight while its session closes: Zookeeper fails it with `CONNECTIONLOSS` before the client
 * state turns `CLOSED`, and the backend reports the session expired, not suspended.
 *
 * A proxy that can stop forwarding holds the request; the short tick keeps the client's read timeout near a second.
 */
class ZookeeperLeaseBackendClosingTest {
    private lateinit var server: TestingServer
    private lateinit var proxy: FreezingProxy
    private lateinit var backend: ZookeeperLeaseBackend

    @BeforeEach
    fun setup() {
        server = TestingServer(InstanceSpec(null, -1, -1, -1, true, -1, TICK_MILLIS, -1), true)
        proxy = FreezingProxy(server.port)
        backend = ZookeeperLeaseBackend("127.0.0.1:${proxy.port}")
    }

    @AfterEach
    fun destroy() {
        backend.close()
        proxy.close()
        server.close()
    }

    @Test
    fun aRequestInFlightWhileItsSessionClosesReportsExpiry() {
        val session = backend.openSession(Duration.ofSeconds(30)).id
        val requested = proxy.freeze()

        val acquire = CompletableFuture<Throwable?>()
        val caller = Thread {
            acquire.complete(runCatching { backend.acquire(session, setOf(LeaseName("closing"))) }.exceptionOrNull())
        }.apply { start() }
        requested.await(30, TimeUnit.SECONDS).assert().isTrue()
        awaitWaitingForAReply(caller)
        backend.closeSession(session)

        acquire.get(30, TimeUnit.SECONDS).assert().isInstanceOf(SessionExpiredException::class.java)
    }

    @Test
    fun aKeepAliveWhoseSyncGetsNoAnswerFails() {
        val session = backend.openSession(Duration.ofSeconds(30)).id
        proxy.freeze()

        // The leader never confirms the sync; the client gives up after its read timeout.
        assertThrows<SimbaException> { backend.keepAlive(session, Duration.ofSeconds(30), emptySet()) }
    }

    @Test
    fun membersFailsWhenNeitherTheWriterNorTheReaderGetsAnAnswer() {
        val session = backend.openSession(Duration.ofSeconds(30)).id
        backend.join(session, "group")
        proxy.freeze()

        assertThrows<SimbaException> { backend.members("group") }
    }

    @Test
    fun membersFallsBackToTheReaderWhenTheWriterGetsSuspended() {
        val other = backend.openSession(Duration.ofSeconds(30)).id
        val writer = backend.openSession(Duration.ofSeconds(30)).id
        backend.join(other, "group")
        backend.join(writer, "group")
        // Only the writer's connection stops: it still looks connected, so members tries it first.
        proxy.freezeClient(localPort(checkNotNull(backend.zooKeeperOf(writer))))

        backend.members("group").assert().contains(other)
    }

    /** The client's local port, which the proxy sees as the connection's remote port. */
    private fun localPort(zooKeeper: ZooKeeper): Int {
        val method = ZooKeeper::class.java.getDeclaredMethod("testableLocalSocketAddress").apply { isAccessible = true }
        return (method.invoke(zooKeeper) as InetSocketAddress).port
    }

    /** Waits until [caller] blocks on a Zookeeper reply, so the close starts while its request is in flight. */
    private fun awaitWaitingForAReply(caller: Thread) {
        val waiting = CompletableFuture<Unit>()
        val poller = Executors.newSingleThreadScheduledExecutor()
        try {
            poller.scheduleWithFixedDelay({
                val parked = caller.state == Thread.State.WAITING
                if (parked && caller.stackTrace.any { it.className == CompletableFuture::class.java.name }) {
                    waiting.complete(Unit)
                }
            }, 0, 10, TimeUnit.MILLISECONDS)
            waiting.get(30, TimeUnit.SECONDS)
        } finally {
            poller.shutdownNow()
        }
    }

    companion object {
        private const val TICK_MILLIS = 100
    }
}
