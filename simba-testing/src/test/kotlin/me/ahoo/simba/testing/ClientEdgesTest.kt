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
import me.ahoo.simba.LockTimeoutException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Schedule
import me.ahoo.simba.Simba
import me.ahoo.simba.Term
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Failure and boundary paths of the 5a primitives on virtual time. */
class ClientEdgesTest {
    private val simba = VirtualSimba()

    @AfterEach
    fun close() {
        simba.close()
    }

    private fun client(configure: Simba.Builder.() -> Unit): Simba = simba.client(configure)

    private fun <T> async(action: () -> T): Pair<Thread, CompletableFuture<T>> {
        val future = CompletableFuture<T>()
        val thread = Thread.ofVirtual().start {
            try {
                future.complete(action())
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return thread to future
    }

    private fun causeOf(future: CompletableFuture<*>): Throwable? =
        (runCatching { future.get(WAIT, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException).cause

    @Test
    fun `rejected work counts as exited, so a stop releases at once`() {
        val client = client { workExecutor { throw RejectedExecutionException("full") } }
        val task = client.election("rejected").runWhileLeader { error("never runs") }
        simba.settle()
        val owner = checkNotNull(simba.backend.owner(LeaseName("rejected")))
        owner.token.assert().isEqualTo(1L)

        task.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.backend.owner(LeaseName("rejected")).assert().isNull()
    }

    @Test
    fun `work revoked before its task starts never runs`() {
        val queued = LinkedBlockingQueue<Runnable>()
        val client = client { workExecutor { queued += it } }
        val ran = LinkedBlockingQueue<Term>()
        val task = client.election("late").runWhileLeader { ran += it }
        val job = queued.next()
        val stopped = task.stop().toCompletableFuture()
        simba.settle()
        stopped.isDone.assert().isFalse()

        job.run()
        stopped.get(WAIT, TimeUnit.SECONDS)
        ran.isEmpty().assert().isTrue()
    }

    @Test
    fun `work failing after its term ended is cancelled, not failed`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Long>()
        val task = client.election("cancelled").runWhileLeader { term ->
            try {
                started += term.token
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                error("interrupted ${term.token}")
            }
        }
        simba.settle()
        // Work cancelled before its thread ran never starts and reports nothing; close only once it runs.
        started.next()
        task.close()
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.CANCELLED)
        SimbaEvent.WorkCompleted(LeaseName("x"), 1, WorkOutcome.COMPLETED, Duration.ZERO).cause.assert().isNull()
    }

    @Test
    fun `a schedule interrupted inside a run ends its loop`() {
        val client = simba.client()
        val started = LinkedBlockingQueue<Long>()
        val task = client.election("sleepy").schedule(Schedule.fixedDelay(Duration.ofSeconds(1))) { term ->
            started += term.token
            Thread.sleep(Long.MAX_VALUE)
        }
        simba.advance(Duration.ZERO)
        started.next()
        task.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.CANCELLED)
    }

    @Test
    fun `a lock waiting for a holder in this client times out`() {
        val client = simba.client()
        val held = client.lock("local").acquire(Duration.ofSeconds(1))
        val (_, waiting) = async { client.lock("local").acquire(Duration.ofSeconds(1)) }
        repeat(MAX_STEPS) {
            if (!waiting.isDone) simba.advance(Duration.ofMillis(100))
        }
        causeOf(waiting).assert().isInstanceOf(LockTimeoutException::class.java)
        held.toString().assert().contains("local")
        held.close()
        assertThrows<IllegalArgumentException> { client.lock("local").acquire(Duration.ofSeconds(-1)) }
    }

    @Test
    fun `an interrupted acquire gives up its attempt`() {
        val holder = simba.client()
        val client = simba.client()
        holder.lock("busy").acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val (thread, waiting) = async { client.lock("busy").acquire(Duration.ofSeconds(10)) }
        simba.acquires.next()
        thread.interrupt()

        causeOf(waiting).assert().isInstanceOf(InterruptedException::class.java)
        simba.settle()
        client.footprint()["running"].assert().isEqualTo(0)
    }

    @Test
    fun `closing the client ends a waiting acquire`() {
        val holder = simba.client()
        val client = simba.client()
        holder.lock("closing").acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val (_, waiting) = async { client.lock("closing").acquire(Duration.ofSeconds(10)) }
        simba.acquires.next()
        val closed = client.closeAsync().toCompletableFuture()
        repeat(MAX_STEPS) {
            if (!closed.isDone) simba.advance(Duration.ofMillis(100))
        }

        causeOf(waiting).assert().isInstanceOf(IllegalStateException::class.java)
        client.footprint().values.sum().assert().isEqualTo(0)
        client.awaitIdle()
    }

    @Test
    fun `a group drops partitions it cannot start and the ones of an expired session`() {
        val client = simba.client()
        client.election("orders.0").start(object : LeadershipListener {
            override fun onElected(term: Term) = Unit

            override fun onRevoked(term: Term, reason: RevokeReason) = Unit
        })
        val group = client.group("orders", 2)
        val task = group.runWhileLeader { _, _ -> }
        simba.settle()
        task.partitions().assert().isEqualTo(setOf(1))
        group.toString().assert().contains("orders")

        simba.backend.closeSession(simba.backend.members("orders").single())
        simba.advance(Duration.ofSeconds(1))
        task.close()
        task.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        task.partitions().assert().isEmpty()
    }

    companion object {
        const val MAX_STEPS = 100
    }
}
