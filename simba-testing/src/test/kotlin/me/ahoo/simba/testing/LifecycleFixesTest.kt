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
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Schedule
import me.ahoo.simba.Term
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Lifecycle ownership of names, lock waits, group stops, timers and event kinds, on virtual time. */
class LifecycleFixesTest {
    private val simba = VirtualSimba()

    @AfterEach
    fun close() {
        simba.close()
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

    @Test
    fun `closing a leader task again never stops a later run of the same name`() {
        val client = simba.client()
        val first = client.election("report").runWhileLeader { }
        first.close()
        val later = Recorder()
        client.election("report").start(later)
        simba.settle()
        val term = later.elected.next()

        first.close()
        first.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.settle()
        term.isValid.assert().isTrue()
        later.revoked.isEmpty().assert().isTrue()
    }

    @Test
    fun `a stop followed at once by a start hands the name to the new listener`() {
        val client = simba.client()
        val old = Recorder()
        val new = Recorder()
        val election = client.election("handoff")
        election.start(old)
        val oldTerm = old.elected.next()
        val stopped = election.stop()
        election.start(new)
        stopped.toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.settle()

        old.revoked.next().assert().isEqualTo(oldTerm to RevokeReason.STOPPED)
        val newTerm = new.elected.next()
        (newTerm.token > oldTerm.token).assert().isTrue()
        new.revoked.isEmpty().assert().isTrue()
        old.elected.isEmpty().assert().isTrue()
    }

    @Test
    fun `stepping down after close is rejected`() {
        val client = simba.client()
        val election = client.election("closed")
        client.close()
        assertThrows<IllegalStateException> { election.stepDown(Duration.ZERO) }
    }

    @Test
    fun `a lock whose first term ends at once keeps contending until its timeout`() {
        val callbacks = ConcurrentLinkedQueue<Runnable>()
        val client = simba.client { callbackExecutor { callbacks += it } }
        val acquired = CompletableFuture<me.ahoo.simba.LockTerm>()
        Thread.ofVirtual().start {
            runCatching { client.lock("flaky").acquire(Duration.ofSeconds(30)) }
                .onSuccess { acquired.complete(it) }
                .onFailure { acquired.completeExceptionally(it) }
        }
        simba.acquires.next()
        simba.settle()
        // The first term is elected but its callback has not run yet; end the session under it.
        val first = checkNotNull(simba.backend.owner(LeaseName("flaky")))
        simba.backend.closeSession(first.session)
        simba.advance(Duration.ofSeconds(1))

        pump(callbacks, acquired)
        val term = acquired.get(WAIT, TimeUnit.SECONDS)
        term.isValid.assert().isTrue()
        (term.token > first.token).assert().isTrue()
        pump(callbacks, term.releaseAsync().toCompletableFuture())
    }

    /** Runs queued callbacks and moves time in small steps until [until] completes. */
    private fun pump(callbacks: ConcurrentLinkedQueue<Runnable>, until: CompletableFuture<*>) {
        repeat(MAX_STEPS) {
            if (!until.isDone) {
                while (true) {
                    (callbacks.poll() ?: break).run()
                }
                simba.settle()
                if (!until.isDone) simba.advance(Duration.ofMillis(100))
            }
        }
    }

    @Test
    fun `stopping a group waits for partitions still handing over from an earlier view`() {
        val first = simba.client()
        val stubborn = Collections.synchronizedSet((0 until PARTITIONS).toMutableSet())
        val release = CountDownLatch(1)
        val task = first.group(GROUP, PARTITIONS).runWhileLeader { _, partition ->
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                while (partition in stubborn && release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // ignored on purpose: this work hands over slowly
                    }
                }
            }
        }
        simba.settle()
        task.partitions().size.assert().isEqualTo(PARTITIONS)
        simba.client().group(GROUP, PARTITIONS).runWhileLeader { _, _ -> }
        simba.settle()
        val kept = task.partitions()
        (kept.size in 1 until PARTITIONS).assert().isTrue()
        stubborn.retainAll((0 until PARTITIONS).toSet() - kept)

        val stopped = task.stop().toCompletableFuture()
        repeat(kept.size) { simba.nextEvent<SimbaEvent.WorkCompleted>() }
        simba.settle()
        stopped.isDone.assert().isFalse()
        release.countDown()
        stopped.get(WAIT, TimeUnit.SECONDS)
    }

    @Test
    fun `lock and schedule timers are cancelled once they are no longer needed`() {
        val client = simba.client()
        val horizon = Duration.ofMinutes(30)
        client.lock("timers").acquire(Duration.ofHours(1)).close()
        simba.settle()
        simba.scheduler.pendingBeyond(horizon).assert().isEqualTo(0)

        val task = client.election("rare").schedule(Schedule.fixedDelay(Duration.ofHours(1))) { }
        simba.advance(Duration.ZERO)
        simba.nextEvent<SimbaEvent.WorkCompleted>()
        simba.scheduler.pendingBeyond(horizon).assert().isEqualTo(1)
        task.close()
        simba.settle()
        simba.scheduler.pendingBeyond(horizon).assert().isEqualTo(0)
    }

    @Test
    fun `events and snapshots tell locks, elections and groups apart, and close reports the session closed`() {
        val client = simba.client()
        val lock = client.lock("invoice-1").acquire(Duration.ofSeconds(1))
        client.election("report").start(Recorder())
        client.group("billing", 1).runWhileLeader { _, _ -> }
        simba.settle()

        val kinds = client.snapshot().elections.associate { it.name.value to it.kind }
        kinds.assert().isEqualTo(
            mapOf(
                "invoice-1" to ElectionKind.LOCK,
                "report" to ElectionKind.ELECTION,
                "billing.0" to ElectionKind.GROUP
            )
        )
        val elected = generateSequence { simba.events.poll() }.filterIsInstance<SimbaEvent.Elected>()
            .associate { it.name.value to it.kind }
        elected.assert().isEqualTo(kinds)
        lock.close()
        simba.nextEvent<SimbaEvent.Revoked>().kind.assert().isEqualTo(ElectionKind.LOCK)

        val session = checkNotNull(client.snapshot().session.id)
        client.close()
        simba.nextEvent<SimbaEvent.SessionClosed>().session.assert().isEqualTo(session)
    }

    companion object {
        const val GROUP = "orders"
        const val PARTITIONS = 8
        const val MAX_STEPS = 200
    }
}
