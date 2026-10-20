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

package me.ahoo.simba

import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaSnapshot
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Event listeners off the loop, the loop-thread guard, closed waits and saturated durations. */
class ListenerAndLoopTest {
    private class GrantingBackend : LeaseBackend {
        override fun openSession(lease: Duration, metadata: Map<String, String>) = SessionGrant(SessionId("s"), lease)

        override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>) =
            KeepAliveResult.Live(lease, emptyMap())

        override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> =
            names.associateWith { AcquireResult.Granted(1) }

        override fun release(session: SessionId, leases: Map<LeaseName, Long>) = Unit

        override fun closeSession(session: SessionId) = Unit

        override fun join(session: SessionId, group: String) = Unit

        override fun leave(session: SessionId, group: String) = Unit

        override fun members(group: String): List<SessionId> = emptyList()
    }

    private object Ignore : LeadershipListener {
        override fun onElected(term: Term) = Unit

        override fun onRevoked(term: Term, reason: RevokeReason) = Unit
    }

    @Test
    fun `event listeners run off the loop and may call blocking client APIs`() {
        val done = LinkedBlockingQueue<SimbaSnapshot>()
        lateinit var client: Simba
        client = Simba.builder().backend(GrantingBackend()).listener { event ->
            if (event is SimbaEvent.Elected && event.name.value == "blocking") {
                val snapshot = client.snapshot()
                // Waiting for a stop from a listener used to deadlock: the stop needs the loop the listener ran on.
                client.election("blocking").stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
                done += snapshot
            }
        }.build()
        client.use {
            it.election("blocking").start(Ignore)
            val snapshot = checkNotNull(done.poll(WAIT, TimeUnit.SECONDS))
            snapshot.elections.single().name.value.assert().isEqualTo("blocking")
        }
    }

    @Test
    fun `on the loop, reads work in place and blocking calls fail fast`() {
        val outcomes = LinkedBlockingQueue<Any>()
        lateinit var client: Simba
        client = Simba.builder()
            .backend(GrantingBackend())
            .ioExecutor(Runnable::run)
            .callbackExecutor(Runnable::run)
            .listener { event ->
                if (event is SimbaEvent.Elected && event.name.value == "inline") {
                    outcomes += client.snapshot().elections.size
                    outcomes += runCatching { client.lock("other").tryAcquire() }.exceptionOrNull()!!
                    outcomes += runCatching { client.lock("another").acquire(Duration.ofSeconds(1)) }.exceptionOrNull()!!
                    outcomes += runCatching { client.awaitIdle() }.exceptionOrNull()!!
                }
            }
            .build()
        client.use {
            it.election("inline").start(Ignore)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isEqualTo(1)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
            // A refused acquire never started an attempt: no election of either lock name, nothing held.
            it.snapshot().elections.map { election -> election.name.value }.assert().containsExactly("inline")
        }
    }

    @Test
    @OptIn(me.ahoo.simba.engine.InternalSimbaApi::class)
    fun `awaitIdle returns without an external scheduler although the loop holds future timers`() {
        Simba.builder().backend(GrantingBackend()).ioExecutor(Runnable::run).build().use { client ->
            client.election("timed").start(Ignore)
            CompletableFuture.supplyAsync { client.awaitIdle() }.get(WAIT, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `closing a lock term in a dependent of acquireAsync does not wait for the callback executor it occupies`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            Simba.builder().backend(GrantingBackend()).callbackExecutor(executor).build().use { client ->
                val lock = client.lock("chained")
                lock.acquireAsync(Duration.ofSeconds(WAIT)).thenAccept { it.close() }
                    .toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
                // The release went through and the single callback thread is still serving the client.
                lock.acquire(Duration.ofSeconds(WAIT)).close()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a backend granting a quarter of the lease is renewed more often and reported`() {
        val shortened = LinkedBlockingQueue<SimbaEvent.SessionLeaseShortened>()
        val quarter = object : LeaseBackend by GrantingBackend() {
            override fun openSession(lease: Duration, metadata: Map<String, String>) =
                SessionGrant(SessionId("short"), lease.dividedBy(4))
        }
        Simba.builder().backend(quarter).listener { event ->
            if (event is SimbaEvent.SessionLeaseShortened) shortened += event
        }.build().use {
            val event = checkNotNull(shortened.poll(WAIT, TimeUnit.SECONDS))
            event.remaining.assert().isEqualTo(Duration.ofMillis(3_750))
            (event.renewEvery < it.config.renewInterval).assert().isTrue()
        }
    }

    @Test
    @OptIn(me.ahoo.simba.engine.InternalSimbaApi::class)
    fun `footprints read in place on the loop and are empty once closed`() {
        val read = LinkedBlockingQueue<Map<String, Int>>()
        lateinit var client: Simba
        client = Simba.builder()
            .backend(GrantingBackend())
            .ioExecutor(Runnable::run)
            .callbackExecutor(Runnable::run)
            .listener { event -> if (event is SimbaEvent.Elected) read += client.footprint() }
            .build()
        client.election("counted").start(Ignore)
        checkNotNull(read.poll(WAIT, TimeUnit.SECONDS))["elections"].assert().isEqualTo(1)
        client.close()
        client.footprint().values.sum().assert().isEqualTo(0)
    }

    @Test
    fun `waiting for a pending stage on the loop fails fast and chaining on it works`() {
        val outcomes = LinkedBlockingQueue<Any>()
        Simba.builder()
            .backend(GrantingBackend())
            .ioExecutor(Runnable::run)
            .callbackExecutor(Runnable::run)
            .build()
            .use { client ->
                val election = client.election("waited")
                election.start(object : LeadershipListener {
                    override fun onElected(term: Term) {
                        val stopping = election.stop()
                        outcomes += runCatching { stopping.toCompletableFuture().join() }.exceptionOrNull()!!
                        stopping.thenRun { outcomes += "stopped" }
                    }

                    override fun onRevoked(term: Term, reason: RevokeReason) = Unit
                })
                outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
                outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isEqualTo("stopped")
                // Off the loop, the same stage converts and waits as usual.
                election.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            }
    }

    @Test
    fun `a failed close future ends a wait as a closed client, not an execution exception`() {
        val failure = IllegalArgumentException("close failed")
        val error = assertThrows<IllegalStateException> {
            awaitAny(CompletableFuture<Void>(), CompletableFuture.failedFuture<Void>(failure), null)
        }
        error.cause.assert().isSameAs(failure)
    }

    @Test
    fun `durations too large for nanoseconds saturate instead of overflowing`() {
        Simba.builder().backend(GrantingBackend()).build().use { client ->
            val term = client.lock("forever").acquire(Duration.ofMillis(Long.MAX_VALUE))
            term.isValid.assert().isTrue()
            term.close()
            val election = client.election("cooling")
            election.start(Ignore)
            election.stepDown(Duration.ofSeconds(Long.MAX_VALUE)).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            client.snapshot().elections.single { it.name.value == "cooling" }.kind.assert().isEqualTo(
                ElectionKind.ELECTION
            )
        }
    }

    private companion object {
        const val WAIT = 5L
    }
}
