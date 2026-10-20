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

import me.ahoo.simba.DistributedLock
import me.ahoo.simba.LockTerm
import me.ahoo.simba.LockTimeoutException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Simba
import me.ahoo.simba.Term
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** `DistributedLock.acquireAsync` / `tryAcquireAsync` on virtual time: no waiting thread, cancellation abandons. */
class AsyncLockTest {
    private val simba = VirtualSimba()
    private val name = LeaseName("invoice-42")

    @AfterEach
    fun close() {
        simba.close()
    }

    private fun Simba.isIdle(): Boolean = footprint().filterValues { it != 0 }.isEmpty()

    private fun <T> CompletionStage<T>.failure(): Throwable? =
        runCatching { toCompletableFuture().get(WAIT, TimeUnit.SECONDS) }.exceptionOrNull()?.let {
            (it as ExecutionException).cause
        }

    @Test
    fun `acquireAsync completes with the term and releasing it frees the lease`() {
        val client = simba.client()
        val acquiring = client.lock(name.value).acquireAsync(Duration.ofSeconds(1))
        simba.settle()

        val term = acquiring.toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        term.token.assert().isEqualTo(1L)
        simba.backend.owner(name).assert().isNotNull()
        term.releaseAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.settle()
        simba.backend.owner(name).assert().isNull()
        client.isIdle().assert().isTrue()
    }

    @Test
    fun `acquireAsync times out on the client clock while another session holds the lease`() {
        val first = simba.client()
        val second = simba.client()
        first.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val waiting = second.lock(name.value).acquireAsync(Duration.ofSeconds(2)).toCompletableFuture()
        simba.acquires.next()

        simba.advance(Duration.ofMillis(1_999))
        waiting.isDone.assert().isFalse()
        simba.advance(Duration.ofMillis(1))
        waiting.failure().assert().isInstanceOf(LockTimeoutException::class.java)
        simba.settle()
        second.isIdle().assert().isTrue()
    }

    @Test
    fun `tryAcquireAsync completes with null while another session holds the lease`() {
        val first = simba.client()
        val second = simba.client()
        val held = first.lock(name.value).acquire(Duration.ofSeconds(1))
        val trying = second.lock(name.value).tryAcquireAsync()
        simba.settle()

        trying.toCompletableFuture().get(WAIT, TimeUnit.SECONDS).assert().isNull()
        simba.settle()
        second.isIdle().assert().isTrue()
        held.close()
    }

    @Test
    fun `contending acquireAsync calls win one after another with increasing tokens`() {
        val first = simba.client()
        val second = simba.client()
        val winner = first.lock(name.value).acquireAsync(Duration.ofSeconds(5)).toCompletableFuture()
        simba.settle()
        val held = winner.get(WAIT, TimeUnit.SECONDS)
        simba.acquires.clear()
        val waiting = second.lock(name.value).acquireAsync(Duration.ofSeconds(5)).toCompletableFuture()
        simba.acquires.next()
        simba.settle()
        waiting.isDone.assert().isFalse()

        held.close()
        simba.settle()
        val next = waiting.get(WAIT, TimeUnit.SECONDS)
        next.token.assert().isGreaterThan(held.token)
        held.isValid.assert().isFalse()
        next.close()
    }

    @Test
    fun `cancelling a waiting acquireAsync abandons the attempt`() {
        val first = simba.client()
        val second = simba.client()
        val held = first.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val waiting = second.lock(name.value).acquireAsync(Duration.ofSeconds(5)).toCompletableFuture()
        simba.acquires.next()
        simba.settle()

        waiting.cancel(false).assert().isTrue()
        simba.settle()
        second.isIdle().assert().isTrue()
        held.close()
        simba.advance(Duration.ofSeconds(1))
        simba.backend.owner(name).assert().isNull()
    }

    @Test
    fun `cancelling an acquireAsync waiting for a local holder frees the name`() {
        val client = simba.client()
        val held = client.lock(name.value).acquire(Duration.ofSeconds(1))
        val waiting = client.lock(name.value).acquireAsync(Duration.ofSeconds(5)).toCompletableFuture()
        simba.settle()

        waiting.cancel(false).assert().isTrue()
        held.close()
        simba.advance(Duration.ofSeconds(1))
        simba.backend.owner(name).assert().isNull()
        client.isIdle().assert().isTrue()
    }

    @Test
    fun `closing the client fails a waiting acquireAsync`() {
        val first = simba.client()
        val second = simba.client()
        first.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val waiting = second.lock(name.value).acquireAsync(Duration.ofSeconds(5)).toCompletableFuture()
        simba.acquires.next()
        simba.settle()

        second.closeAsync()
        simba.settle()
        waiting.failure().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `interrupting a blocking acquire abandons the attempt`() {
        val first = simba.client()
        val second = simba.client()
        val held = first.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val outcome = CompletableFuture<Throwable?>()
        val waiter = Thread.ofVirtual().start {
            outcome.complete(runCatching { second.lock(name.value).acquire(Duration.ofSeconds(5)) }.exceptionOrNull())
        }
        simba.acquires.next()
        simba.settle()

        waiter.interrupt()
        outcome.get(WAIT, TimeUnit.SECONDS).assert().isInstanceOf(InterruptedException::class.java)
        simba.settle()
        second.isIdle().assert().isTrue()
        held.close()
    }

    @Test
    fun `tryAcquireAsync completes with null while this client holds the name`() {
        val client = simba.client()
        val held = client.lock(name.value).acquire(Duration.ofSeconds(1))

        client.lock(name.value).tryAcquireAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS).assert().isNull()
        held.close()
    }

    @Test
    fun `acquireAsync waiting for a local holder times out on the client clock`() {
        val client = simba.client()
        val held = client.lock(name.value).acquire(Duration.ofSeconds(1))
        val waiting = client.lock(name.value).acquireAsync(Duration.ofSeconds(2)).toCompletableFuture()
        simba.settle()

        simba.advance(Duration.ofSeconds(2))
        waiting.failure().assert().isInstanceOf(LockTimeoutException::class.java)
        held.close()
        simba.settle()
        client.isIdle().assert().isTrue()
    }

    @Test
    fun `closing the client fails an acquireAsync waiting for a local holder`() {
        val client = simba.client()
        client.lock(name.value).acquire(Duration.ofSeconds(1))
        val waiting = client.lock(name.value).acquireAsync(Duration.ofSeconds(5)).toCompletableFuture()
        simba.settle()

        client.closeAsync()
        simba.settle()
        waiting.failure().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the default async methods complete with what the blocking calls return or throw`() {
        val lock = BlockingLock { null }

        lock.tryAcquireAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS).assert().isNull()
        lock.acquireAsync(Duration.ofSeconds(1)).failure().assert().isInstanceOf(LockTimeoutException::class.java)
    }

    @Test
    fun `a negative timeout fails the returned future and throws from the blocking call`() {
        val lock = simba.client().lock(name.value)

        lock.acquireAsync(Duration.ofSeconds(-1)).failure().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { lock.acquire(Duration.ofSeconds(-1)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a term revoked before the completion runs is not returned and the lock contends again`() {
        val holding = HoldingExecutor()
        val client = simba.client { callbackExecutor(holding) }
        val acquiring = client.lock(name.value).acquireAsync(Duration.ofSeconds(10)).toCompletableFuture()
        simba.settle()
        val first = holding.completions.next()
        val owner = checkNotNull(simba.backend.owner(name))
        owner.token.assert().isEqualTo(1L)

        simba.backend.closeSession(owner.session)
        simba.advance(Duration.ofSeconds(1))
        first.run()
        acquiring.isDone.assert().isFalse()
        var next = holding.completions.poll()
        repeat(RETRY_STEPS) {
            if (next == null) {
                simba.advance(Duration.ofMillis(100))
                next = holding.completions.poll()
            }
        }
        checkNotNull(next) { "The lock did not contend again." }.run()

        val term = acquiring.get(WAIT, TimeUnit.SECONDS)
        term.token.assert().isEqualTo(2L)
        term.isValid.assert().isTrue()
        term.close()
    }

    /** Runs everything at once except lock completions, which it holds for the test to run. */
    private class HoldingExecutor : Executor {
        val completions = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            if (command.toString().startsWith("LockCompletion")) completions += command else command.run()
        }
    }

    @Test
    fun `cancelled acquisitions leave no waiter on the client's close future`() {
        val first = simba.client()
        val second = simba.client()
        val held = first.lock(name.value).acquire(Duration.ofSeconds(1))
        val local = second.lock("local").acquire(Duration.ofSeconds(1))
        simba.settle()

        repeat(CANCELLED_ACQUIRES) {
            // One waiting on another session, one waiting for a holder of this client.
            second.lock(name.value).acquireAsync(Duration.ofMinutes(10)).toCompletableFuture().cancel(false)
            second.lock("local").acquireAsync(Duration.ofMinutes(10)).toCompletableFuture().cancel(false)
        }
        simba.settle()

        second.footprint()["closeWaiters"].assert().isEqualTo(0)
        local.close()
        simba.settle()
        second.isIdle().assert().isTrue()
        held.close()
    }

    @Test
    fun `a term hands out one revocation stage for every read`() {
        val term = simba.client().lock(name.value).acquire(Duration.ofSeconds(1))

        (term.revoked === term.revoked).assert().isTrue()
        term.close()
    }

    @Test
    fun `the default acquireAsync interrupts a cancelled blocking acquire`() {
        val started = CountDownLatch(1)
        val interrupted = CompletableFuture<Boolean>()
        val lock = BlockingLock {
            started.countDown()
            try {
                Thread.sleep(Duration.ofMinutes(1))
                null
            } catch (_: InterruptedException) {
                interrupted.complete(true)
                null
            }
        }
        val acquiring = lock.acquireAsync(Duration.ofSeconds(1)).toCompletableFuture()
        started.await(WAIT, TimeUnit.SECONDS).assert().isTrue()

        acquiring.cancel(false)
        interrupted.get(WAIT, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `the default acquireAsync releases a term won after cancellation`() {
        val proceed = CountDownLatch(1)
        val released = LinkedBlockingQueue<Boolean>()
        val term = object : LockTerm, Term by FixedTerm {
            override fun releaseAsync(): CompletionStage<Void> {
                released += true
                return CompletableFuture.completedFuture(null)
            }

            override fun close() {
                releaseAsync()
            }
        }
        val lock = BlockingLock {
            proceed.awaitUninterruptibly()
            term
        }
        val trying = lock.tryAcquireAsync().toCompletableFuture()

        trying.cancel(false)
        proceed.countDown()
        released.next().assert().isTrue()
    }

    @Test
    fun `the default tryAcquireAsync ignores a null result after cancellation`() {
        val proceed = CountDownLatch(1)
        val returned = CompletableFuture<Unit>()
        val lock = BlockingLock {
            proceed.awaitUninterruptibly()
            returned.complete(Unit)
            null
        }
        val trying = lock.tryAcquireAsync().toCompletableFuture()

        trying.cancel(false)
        proceed.countDown()
        returned.get(WAIT, TimeUnit.SECONDS)
        trying.isCancelled.assert().isTrue()
    }

    private fun CountDownLatch.awaitUninterruptibly() {
        while (true) {
            try {
                await()
                return
            } catch (_: InterruptedException) {
                // Keep waiting: the test releases the latch after cancelling.
            }
        }
    }

    /** A lock of another implementation, which inherits the default async methods. */
    private class BlockingLock(private val body: () -> LockTerm?) : DistributedLock {
        override val name = LeaseName("blocking")

        override fun acquire(timeout: Duration): LockTerm = body() ?: throw LockTimeoutException(name, timeout)

        override fun tryAcquire(): LockTerm? = body()
    }

    private object FixedTerm : Term {
        override val name = LeaseName("blocking")
        override val token = 1L
        override val isValid = true
        override val revoked: CompletionStage<RevokeReason> = CompletableFuture()

        override fun remaining(): Duration = Duration.ofSeconds(1)

        override fun ensureValid(minRemaining: Duration) = Unit
    }

    companion object {
        const val RETRY_STEPS = 100
        const val CANCELLED_ACQUIRES = 1_000
    }
}
