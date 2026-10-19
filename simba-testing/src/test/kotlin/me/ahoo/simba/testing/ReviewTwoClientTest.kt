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
import me.ahoo.simba.Schedule
import me.ahoo.simba.Term
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.ElectionSnapshot
import me.ahoo.simba.event.SessionSnapshot
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaSnapshot
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Errors in leader work, a rejecting callback executor, groups after close, and the shape of public value types. */
class ReviewTwoClientTest {
    private val simba = VirtualSimba()

    @AfterEach
    fun close() {
        simba.close()
    }

    @Test
    fun `a schedule run throwing an Error is reported as failed and the loop goes on`() {
        val client = simba.client()
        var calls = 0
        client.election("asserting").schedule(Schedule.fixedDelay(Duration.ofSeconds(1))) {
            if (++calls == 1) {
                throw AssertionError("first run fails")
            }
        }
        simba.advance(Duration.ZERO)
        val failed = simba.nextEvent<SimbaEvent.WorkCompleted>()
        failed.outcome.assert().isEqualTo(WorkOutcome.FAILED)
        failed.cause.assert().isInstanceOf(AssertionError::class.java)
        simba.advance(Duration.ofSeconds(1))
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.COMPLETED)
    }

    @Test
    fun `work hitting a VirtualMachineError reports it, steps down and gives the lease up for one lease duration`() {
        val client = simba.client()
        val terms = LinkedBlockingQueue<Term>()
        client.election("fatal").runWhileLeader { term ->
            terms += term
            if (term.token == 1L) {
                throw StackOverflowError("deep")
            }
        }
        val first = terms.next()
        val failed = simba.nextEvent<SimbaEvent.WorkCompleted>()
        failed.outcome.assert().isEqualTo(WorkOutcome.FAILED)
        failed.cause.assert().isInstanceOf(StackOverflowError::class.java)
        first.revoked.toCompletableFuture().get(WAIT, TimeUnit.SECONDS).assert().isEqualTo(RevokeReason.STEPPED_DOWN)
        simba.settle()
        simba.backend.owner(LeaseName("fatal")).assert().isNull()

        simba.advance(simba.config.leaseDuration.minusMillis(1))
        terms.isEmpty().assert().isTrue()
        simba.advance(Duration.ofMillis(1))
        terms.next().token.assert().isEqualTo(2L)
    }

    @Test
    fun `a schedule run hitting a VirtualMachineError ends the loop and steps down`() {
        val client = simba.client()
        val tokens = LinkedBlockingQueue<Long>()
        client.election("fatal-schedule").schedule(Schedule.fixedDelay(Duration.ofSeconds(1))) { term ->
            tokens += term.token
            throw OutOfMemoryError("simulated")
        }
        simba.advance(Duration.ZERO)
        tokens.next().assert().isEqualTo(1L)
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.FAILED)
        simba.nextEvent<SimbaEvent.Revoked>().reason.assert().isEqualTo(RevokeReason.STEPPED_DOWN)
        simba.advance(Duration.ofSeconds(1))
        tokens.isEmpty().assert().isTrue()
    }

    @Test
    fun `a fatal error after the term ended does not step a later term down`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Term>()
        val task = client.election("late-fatal").runWhileLeader { term ->
            started += term
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                throw StackOverflowError("after the stop")
            }
        }
        started.next()
        task.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.FAILED)
        simba.settle()
        simba.events.none { it is SimbaEvent.Revoked && it.reason == RevokeReason.STEPPED_DOWN }.assert().isTrue()
    }

    @Test
    fun `a rejecting callback executor still lets stop and close complete`() {
        val reject = AtomicBoolean(false)
        val client = simba.client {
            callbackExecutor { task -> if (reject.get()) throw RejectedExecutionException("down") else task.run() }
        }
        val election = client.election("rejected")
        val elected = LinkedBlockingQueue<Term>()
        election.start(object : me.ahoo.simba.LeadershipListener {
            override fun onElected(term: Term) {
                elected += term
            }

            override fun onRevoked(term: Term, reason: RevokeReason) = Unit
        })
        val term = elected.next()
        reject.set(true)
        election.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        term.isValid.assert().isFalse()
        client.closeAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
    }

    @Test
    fun `a closed client reports no partitions`() {
        val client = simba.client()
        val task = client.group("billing", 2).runWhileLeader { _, _ -> }
        simba.settle()
        task.partitions().assert().isEqualTo(setOf(0, 1))
        client.close()
        task.partitions().assert().isEmpty()
    }

    @Test
    fun `work events carry the kind of their election`() {
        val client = simba.client()
        client.group("kinds", 1).runWhileLeader { _, _ -> }
        simba.nextEvent<SimbaEvent.WorkCompleted>().kind.assert().isEqualTo(ElectionKind.GROUP)
    }

    @Test
    fun `public value types are plain classes without copy or destructuring`() {
        val types = SimbaEvent::class.java.declaredClasses.toList() + listOf(
            SimbaSnapshot::class.java,
            SessionSnapshot::class.java,
            ElectionSnapshot::class.java,
            me.ahoo.simba.SimbaConfig::class.java,
            SimulationParams::class.java,
            SimulationResult::class.java,
            InMemoryLeaseBackend.Owner::class.java,
        )
        types.size.assert().isGreaterThan(SimbaEvent::class.java.declaredClasses.size)
        for (type in types) {
            type.methods.map { it.name }.filter { it == "copy" || it.startsWith("component") }.assert().isEmpty()
        }
        val event = SimbaEvent.Elected(LeaseName("a"), 1, ElectionKind.LOCK)
        event.assert().isEqualTo(SimbaEvent.Elected(LeaseName("a"), 1, ElectionKind.LOCK))
        event.hashCode().assert().isEqualTo(SimbaEvent.Elected(LeaseName("a"), 1, ElectionKind.LOCK).hashCode())
        event.toString().assert().isEqualTo("Elected(name=a, token=1, kind=LOCK)")
        (event == SimbaEvent.Elected(LeaseName("a"), 2, ElectionKind.LOCK)).assert().isFalse()
        SimulationParams().assert().isEqualTo(SimulationParams())
    }
}
