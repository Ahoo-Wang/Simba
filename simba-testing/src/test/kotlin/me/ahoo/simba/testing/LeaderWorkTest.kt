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

import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Term
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `runWhileLeader` on virtual time: one run per term, interrupted on revocation, handed over before release. */
class LeaderWorkTest {
    private val simba = VirtualSimba()

    @AfterEach
    fun close() {
        simba.close()
    }

    @Test
    fun `work runs once per term and is interrupted and waited for on stop before the release`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Term>()
        val order = LinkedBlockingQueue<String>()
        val task = client.election("report").runWhileLeader { term ->
            started += term
            try {
                term.revoked.toCompletableFuture().get()
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                order += "interrupted, owner=${simba.backend.owner(term.name)?.token}"
            }
        }
        val term = started.next()
        term.token.assert().isEqualTo(1L)
        term.isValid.assert().isTrue()

        task.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        order.next().assert().isEqualTo("interrupted, owner=1")
        term.revoked.toCompletableFuture().get().assert().isEqualTo(RevokeReason.STOPPED)
        simba.backend.owner(term.name).assert().isNull()
        val completed = simba.nextEvent<SimbaEvent.WorkCompleted>()
        completed.outcome.assert().isEqualTo(WorkOutcome.COMPLETED)
        completed.token.assert().isEqualTo(1L)
    }

    @Test
    fun `stop waits at most the term's remaining time for work that ignores interrupts`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Term>()
        val release = CountDownLatch(1)
        val task = client.election("stubborn").runWhileLeader { term ->
            started += term
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // ignored on purpose
                }
            }
        }
        val term = started.next()
        val remaining = term.remaining()
        val stopped = task.stop().toCompletableFuture()
        simba.settle()
        term.isValid.assert().isFalse()
        stopped.isDone.assert().isFalse()
        simba.backend.owner(term.name)?.token.assert().isEqualTo(term.token)

        simba.advance(remaining.minusMillis(1))
        stopped.isDone.assert().isFalse()
        simba.advance(Duration.ofMillis(1))
        stopped.get(WAIT, TimeUnit.SECONDS)
        simba.backend.owner(term.name).assert().isNull()
        release.countDown()
    }

    @Test
    fun `a failing work is reported and does not end the term`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Term>()
        client.election("failing").runWhileLeader { term ->
            started += term
            error("boom")
        }
        val term = started.next()
        val completed = simba.nextEvent<SimbaEvent.WorkCompleted>()
        completed.outcome.assert().isEqualTo(WorkOutcome.FAILED)
        completed.cause?.message.assert().isEqualTo("boom")
        simba.advance(Duration.ofSeconds(5))
        term.isValid.assert().isTrue()
    }

    @Test
    fun `a step-down hands over and the next term runs the work again with a new token`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Term>()
        val election = client.election("handover")
        election.runWhileLeader { term ->
            started += term
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val first = started.next()
        election.stepDown(Duration.ofSeconds(2)).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        first.revoked.toCompletableFuture().get().assert().isEqualTo(RevokeReason.STEPPED_DOWN)
        simba.backend.owner(first.name).assert().isNull()

        simba.advance(Duration.ofSeconds(2))
        val second = started.next()
        second.token.assert().isEqualTo(first.token + 1)
        first.isValid.assert().isFalse()
    }

    @Test
    fun `work runs on the given executor and another client takes over after stop`() {
        val runs = AtomicInteger()
        val executor = Executor { command ->
            runs.incrementAndGet()
            Thread.ofVirtual().start(command)
        }
        val first = simba.client()
        val second = simba.client()
        val terms = LinkedBlockingQueue<Term>()
        val task = first.election("shared").runWhileLeader(executor) { term -> terms += term }
        second.election("shared").runWhileLeader { term -> terms += term }
        val one = terms.next()
        // Let the second client's first attempt answer, so the release hint finds it idle.
        simba.settle()
        task.close()
        simba.settle()
        val two = terms.next()

        (two.token > one.token).assert().isTrue()
        runs.get().assert().isEqualTo(1)
    }
}
