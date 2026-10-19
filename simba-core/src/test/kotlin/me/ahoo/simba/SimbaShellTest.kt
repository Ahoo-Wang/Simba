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

import me.ahoo.simba.engine.EngineConfig
import me.ahoo.simba.engine.EngineShell
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaShellTest {
    /** Grants every lease; enough to drive the shell without simba-testing. */
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

    @Test
    fun `stopped one-off elections leave no dispatcher behind`() {
        Simba.builder().backend(GrantingBackend()).build().use { client ->
            val elected = LinkedBlockingQueue<Term>()
            val listener = object : LeadershipListener {
                override fun onElected(term: Term) {
                    elected += term
                }

                override fun onRevoked(term: Term, reason: RevokeReason) = Unit
            }
            repeat(ONE_OFF_NAMES) { index ->
                val election = client.election("invoice-$index")
                election.start(listener)
                elected.poll(WAIT, TimeUnit.SECONDS).assert().isNotNull()
                election.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            }
            client.election("kept").start(listener)
            elected.poll(WAIT, TimeUnit.SECONDS).assert().isNotNull()

            client.dispatcherCount().assert().isEqualTo(1)
        }
    }

    @Test
    fun `builder options, listeners and failing callbacks`() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val events = LinkedBlockingQueue<me.ahoo.simba.event.SimbaEvent>()
        val client = Simba.builder()
            .backend(GrantingBackend())
            .config(SimbaConfig())
            .ioExecutor(executor)
            .callbackExecutor(executor)
            .ticker(me.ahoo.simba.spi.Ticker.SYSTEM)
            .seed(1)
            .listener { events += it }
            .listener { error("listener failure is ignored") }
            .build()
        val elected = LinkedBlockingQueue<Term>()
        client.election("failing").start(object : LeadershipListener {
            override fun onElected(term: Term) {
                elected += term
                error("callback failure is ignored")
            }

            override fun onRevoked(term: Term, reason: RevokeReason) = Unit
        })
        elected.poll(WAIT, TimeUnit.SECONDS).assert().isNotNull()
        client.closeAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        client.closeAsync().toCompletableFuture().isDone.assert().isTrue()
        executor.shutdown()
        events.map { it.javaClass.simpleName }.assert().contains("SessionOpened", "Elected", "Revoked")
    }

    @Test
    fun `closing from a callback does not wait for itself`() {
        val client = Simba.builder().backend(GrantingBackend()).build()
        val closed = java.util.concurrent.CompletableFuture<Unit>()
        client.election("closer").start(object : LeadershipListener {
            override fun onElected(term: Term) {
                client.close()
                closed.complete(Unit)
            }

            override fun onRevoked(term: Term, reason: RevokeReason) = Unit
        })
        closed.get(WAIT, TimeUnit.SECONDS)
        client.closeAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
    }

    @Test
    fun `misuse is rejected`() {
        assertThrows<IllegalArgumentException> { Simba.builder().build() }
        val client = Simba.builder().backend(GrantingBackend()).build()
        val election = client.election("misuse")
        election.name.assert().isEqualTo(LeaseName("misuse"))
        election.stop().toCompletableFuture().isDone.assert().isTrue()
        assertThrows<IllegalArgumentException> { election.stepDown(Duration.ofSeconds(-1)) }
        election.stepDown(Duration.ZERO).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        client.close()
        assertThrows<IllegalStateException> { client.election("late") }
        assertThrows<IllegalStateException> { election.start(NoopListener) }
        election.stop().toCompletableFuture().isDone.assert().isTrue()
    }

    @Test
    fun `the shell never swaps the listener of a started election and completes a step-down after close`() {
        val shell = EngineShell(
            backend = GrantingBackend(),
            config = EngineConfig.of(SimbaConfig()),
            ticker = me.ahoo.simba.spi.Ticker.SYSTEM,
            ioExecutor = Runnable::run,
            callbackExecutor = Runnable::run,
            eventListeners = emptyList(),
            seed = 1,
        )
        shell.boot()
        val first = LinkedBlockingQueue<Term>()
        val second = LinkedBlockingQueue<Term>()
        val name = LeaseName("guarded")
        shell.start(name, recording(first))
        val probe = java.util.concurrent.CompletableFuture<Void>()
        shell.start(name, recording(second), probe = probe)
        probe.get(WAIT, TimeUnit.SECONDS)
        first.poll(WAIT, TimeUnit.SECONDS).assert().isNotNull()
        second.isEmpty().assert().isTrue()

        shell.close().get(WAIT, TimeUnit.SECONDS)
        shell.stepDown(name, 0).get(WAIT, TimeUnit.SECONDS)
    }

    private fun recording(elected: LinkedBlockingQueue<Term>) = object : LeadershipListener {
        override fun onElected(term: Term) {
            elected += term
        }

        override fun onRevoked(term: Term, reason: RevokeReason) = Unit
    }

    private object NoopListener : LeadershipListener {
        override fun onElected(term: Term) = Unit

        override fun onRevoked(term: Term, reason: RevokeReason) = Unit
    }

    companion object {
        const val ONE_OFF_NAMES = 200
        const val WAIT = 5L
    }
}
