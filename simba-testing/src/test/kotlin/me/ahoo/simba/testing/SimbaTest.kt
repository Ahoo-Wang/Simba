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

package me.ahoo.simba.testing

import me.ahoo.simba.LeadershipListener
import me.ahoo.simba.TermExpiredException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Simba
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.Term
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The threaded shell against the in-memory backend on the system clock; synchronizes on queues, never sleeps. */
class SimbaTest {
    private val backend = InMemoryLeaseBackend()
    private val config = SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofMillis(500)).build()
    private val clients = ArrayList<Simba>()

    private fun client(): Simba =
        Simba.builder().backend(backend).config(config).build().also(clients::add)

    @AfterEach
    fun close() {
        clients.forEach(Simba::close)
    }

    private class Recorder : LeadershipListener {
        val elected = LinkedBlockingQueue<Term>()
        val revoked = LinkedBlockingQueue<Pair<Term, RevokeReason>>()

        override fun onElected(term: Term) {
            elected += term
        }

        override fun onRevoked(term: Term, reason: RevokeReason) {
            revoked += term to reason
        }
    }

    private fun <T> LinkedBlockingQueue<T>.next(): T = checkNotNull(poll(WAIT, TimeUnit.SECONDS)) { "timed out" }

    @Test
    fun `one client is elected and the other takes over after stop`() {
        val first = Recorder()
        val second = Recorder()
        client().election("orders").start(first)
        val firstTerm = first.elected.next()
        client().election("orders").start(second)

        firstTerm.isValid.assert().isTrue()
        firstTerm.token.assert().isEqualTo(1L)
        clients[0].election("orders").stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)

        first.revoked.next().assert().isEqualTo(firstTerm to RevokeReason.STOPPED)
        firstTerm.isValid.assert().isFalse()
        firstTerm.remaining().assert().isEqualTo(Duration.ZERO)
        assertThrows<TermExpiredException> { firstTerm.ensureValid() }
        second.elected.next().token.assert().isEqualTo(2L)
    }

    @Test
    fun `step down revokes, re-elects after the cooldown with a new token`() {
        val recorder = Recorder()
        val election = client().election("report")
        election.start(recorder)
        val first = recorder.elected.next()
        election.stepDown(Duration.ofMillis(100)).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)

        recorder.revoked.next().assert().isEqualTo(first to RevokeReason.STEPPED_DOWN)
        first.revoked.toCompletableFuture().get().assert().isEqualTo(RevokeReason.STEPPED_DOWN)
        val second = recorder.elected.next()
        (second.token > first.token).assert().isTrue()
    }

    @Test
    fun `close revokes every term and frees the leases at once`() {
        val recorder = Recorder()
        val owner = client()
        owner.election("billing").start(recorder)
        val term = recorder.elected.next()
        owner.close()

        recorder.revoked.next().assert().isEqualTo(term to RevokeReason.STOPPED)
        backend.owner(term.name).assert().isNull()
    }

    @Test
    fun `starting an election twice is rejected`() {
        val election = client().election("twice")
        election.start(Recorder())
        assertThrows<IllegalStateException> { election.start(Recorder()) }
    }

    companion object {
        const val WAIT = 5L
    }
}
