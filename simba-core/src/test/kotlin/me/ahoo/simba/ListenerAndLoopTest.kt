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
                    outcomes += runCatching { client.awaitIdle() }.exceptionOrNull()!!
                }
            }
            .build()
        client.use {
            it.election("inline").start(Ignore)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isEqualTo(1)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
            outcomes.poll(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
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
            client.snapshot().elections.single { it.name.value == "cooling" }.kind.assert().isEqualTo(ElectionKind.ELECTION)
        }
    }

    private companion object {
        const val WAIT = 5L
    }
}
