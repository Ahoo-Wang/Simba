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

package me.ahoo.simba.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.simba.DistributedLock
import me.ahoo.simba.LockTerm
import me.ahoo.simba.LockTimeoutException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Simba
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.Term
import me.ahoo.simba.engine.InternalSimbaApi
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.testing.InMemoryLeaseBackend
import me.ahoo.simba.testing.VirtualScheduler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The coroutine adapters over threaded clients on virtual time (direct I/O and callbacks). */
@OptIn(InternalSimbaApi::class)
class CoroutinesTest {
    private val scheduler = VirtualScheduler()
    private val sessions = AtomicInteger()
    private val backend = InMemoryLeaseBackend(scheduler) { SessionId("session-${sessions.incrementAndGet()}") }
    private val acquires = LinkedBlockingQueue<Set<LeaseName>>()
    private val recording = object : LeaseBackend by backend {
        override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> {
            acquires += names
            return backend.acquire(session, names)
        }
    }
    private val clients = ArrayList<Simba>()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val name = LeaseName("invoice-42")

    private fun client(configure: Simba.Builder.() -> Unit = {}): Simba =
        Simba.builder()
            .backend(recording)
            .config(
                SimbaConfig.builder()
                    .leaseDuration(Duration.ofSeconds(3))
                    .renewInterval(Duration.ofSeconds(1))
                    .margin(Duration.ofMillis(20))
                    .retryJitter(Duration.ZERO)
                    .build(),
            )
            .scheduler(scheduler)
            .ioExecutor(Runnable::run)
            .callbackExecutor(Runnable::run)
            .seed(clients.size.toLong())
            .apply(configure)
            .build()
            .also {
                clients += it
                settle()
            }

    private fun settle() {
        var last: List<Long>? = null
        while (true) {
            val handled = clients.map { it.awaitIdle() }
            if (handled == last) {
                return
            }
            last = handled
        }
    }

    private fun advance(duration: Duration) {
        scheduler.advance(duration, ::settle)
    }

    private fun Simba.isIdle(): Boolean = footprint().filterValues { it != 0 }.isEmpty()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(WAIT_MILLIS) { block() } }

    @AfterEach
    fun close() {
        scope.cancel()
        clients.forEach { it.closeAsync() }
        clients.forEach {
            settle()
            it.closeAsync().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `withLock runs the block with the term and releases it afterwards`() {
        val client = client()
        val token = await { client.lock(name.value).withLock(Duration.ofSeconds(1)) { term -> term.token } }

        token.assert().isEqualTo(1L)
        settle()
        backend.owner(name).assert().isNull()
        client.isIdle().assert().isTrue()
    }

    @Test
    fun `withLock releases the term when the block throws`() {
        val client = client()
        val failure = runCatching {
            await { client.lock(name.value).withLock(Duration.ofSeconds(1)) { error("boom") } }
        }.exceptionOrNull()

        failure.assert().isInstanceOf(IllegalStateException::class.java)
        settle()
        backend.owner(name).assert().isNull()
    }

    @Test
    fun `awaitAcquire throws LockTimeoutException once the timeout passes on the client clock`() {
        val first = client()
        val second = client()
        val held = await { first.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }
        acquires.clear()
        val waiting = scope.async { second.lock(name.value).awaitAcquire(Duration.ofSeconds(2)) }
        acquires.poll(WAIT_SECONDS, TimeUnit.SECONDS).assert().isNotNull()

        advance(Duration.ofSeconds(2))
        runCatching { await { waiting.await() } }.exceptionOrNull()
            .assert().isInstanceOf(LockTimeoutException::class.java)
        held.close()
    }

    @Test
    fun `awaitTryAcquire returns null while another session holds the lease`() {
        val first = client()
        val second = client()
        val held = await { first.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }

        await { second.lock(name.value).awaitTryAcquire() }.assert().isNull()
        held.close()
    }

    @Test
    fun `cancelling a waiting awaitAcquire abandons the attempt`() {
        val first = client()
        val second = client()
        val held = await { first.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }
        acquires.clear()
        val waiting = scope.async { second.lock(name.value).awaitAcquire(Duration.ofSeconds(5)) }
        acquires.poll(WAIT_SECONDS, TimeUnit.SECONDS).assert().isNotNull()
        settle()

        await { waiting.cancel() }
        settle()
        second.isIdle().assert().isTrue()
        held.close()
        advance(Duration.ofSeconds(1))
        backend.owner(name).assert().isNull()
    }

    @Test
    fun `awaitRevoked returns the reason once the term ends and cancelling it leaves the term alone`() {
        val client = client()
        val term = await { client.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }
        val abandoned = scope.async { term.awaitRevoked() }
        await { abandoned.cancel() }
        term.isValid.assert().isTrue()

        val revoked = scope.async { term.awaitRevoked() }
        term.close()
        await { revoked.await() }.assert().isEqualTo(RevokeReason.STOPPED)
    }

    @Test
    fun `SimbaEventFlow publishes the client's events to collectors`() {
        val flow = SimbaEventFlow()
        val subscribed = CompletableDeferred<Unit>()
        val elected = scope.async {
            flow.events.onSubscription { subscribed.complete(Unit) }.filterIsInstance<SimbaEvent.Elected>().first()
        }
        await { subscribed.await() }
        val client = client { listener(flow) }
        val term = await { client.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }

        await { elected.await() }.name.assert().isEqualTo(name)
        term.close()
    }

    @Test
    fun `awaitRevoked rethrows the cause of a failed revocation`() {
        val term = FakeTerm(CompletableFuture.failedFuture(CompletionException(IllegalStateException("boom"))))

        runCatching { await { term.awaitRevoked() } }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a term that arrives after the coroutine was cancelled is released`() {
        val acquiring = CompletableFuture<LockTerm?>()
        val released = CompletableFuture<Unit>()
        val lock = object : DistributedLock {
            override val name = LeaseName("fake")

            override fun acquire(timeout: Duration): LockTerm = error("not used")

            override fun tryAcquire(): LockTerm? = error("not used")

            override fun tryAcquireAsync(): CompletionStage<LockTerm?> = acquiring
        }
        val term = object : LockTerm, Term by FakeTerm(CompletableFuture()) {
            override fun releaseAsync(): CompletionStage<Void> {
                released.complete(Unit)
                return CompletableFuture.completedFuture(null)
            }

            override fun close() {
                releaseAsync()
            }
        }

        runBlocking {
            val waiting = launch { lock.awaitTryAcquire() }
            yield()
            // The resumption is queued on this event loop; cancelling before it runs drops the term.
            acquiring.complete(term)
            waiting.cancel()
        }
        released.isDone.assert().isTrue()
    }

    @Test
    fun `cancelled awaitRevoked waits leave no callbacks behind on the term`() {
        val revoked = CompletableFuture<RevokeReason>()
        val term = FakeTerm(revoked)

        runBlocking {
            repeat(CANCELLED_WAITS) {
                val waiting = launch { term.awaitRevoked() }
                yield()
                waiting.cancel()
            }
            revoked.numberOfDependents.assert().isEqualTo(1)
            val waiting = async { term.awaitRevoked() }
            yield()
            revoked.complete(RevokeReason.STOPPED)
            waiting.await().assert().isEqualTo(RevokeReason.STOPPED)
        }
        runBlocking { term.awaitRevoked() }.assert().isEqualTo(RevokeReason.STOPPED)
    }

    @Test
    fun `awaitRevoked registers once per term even when each read of revoked is a new stage`() {
        val revoked = CompletableFuture<RevokeReason>()
        // Like a term that hands out a fresh minimal stage on every read of `revoked`.
        val term = object : Term by FakeTerm(revoked) {
            override val revoked: CompletionStage<RevokeReason> get() = revoked.minimalCompletionStage()
        }

        runBlocking {
            repeat(CANCELLED_WAITS) {
                val waiting = launch { term.awaitRevoked() }
                yield()
                waiting.cancel()
            }
        }
        revoked.numberOfDependents.assert().isEqualTo(1)
    }

    @Test
    fun `cancelled awaitRevoked waits on a real lock term leave one registration on its revocation`() {
        val client = client()
        val term = await { client.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }
        val revocation = revokedFutureOf(term)

        runBlocking {
            repeat(CANCELLED_WAITS) {
                val waiting = launch { term.awaitRevoked() }
                yield()
                waiting.cancel()
            }
        }
        // Only the term's own cached stage; the one shared relay registers on that stage, not on the future.
        revocation.numberOfDependents.assert().isEqualTo(1)
        val waiting = scope.async { term.awaitRevoked() }
        term.close()
        await { waiting.await() }.assert().isEqualTo(RevokeReason.STOPPED)
    }

    @Test
    fun `a term delivered by a non-future stage after cancellation is released`() {
        val acquiring = CompletableFuture<LockTerm?>()
        val released = CompletableFuture<Unit>()
        val lock = object : DistributedLock {
            override val name = LeaseName("minimal")

            override fun acquire(timeout: Duration): LockTerm = error("not used")

            override fun tryAcquire(): LockTerm? = error("not used")

            // Not a CompletableFuture: toCompletableFuture() is a copy, so cancelling it does not reach this attempt.
            override fun tryAcquireAsync(): CompletionStage<LockTerm?> = acquiring.minimalCompletionStage()
        }
        val term = object : LockTerm, Term by FakeTerm(CompletableFuture()) {
            override fun releaseAsync(): CompletionStage<Void> {
                released.complete(Unit)
                return CompletableFuture.completedFuture(null)
            }

            override fun close() {
                releaseAsync()
            }
        }

        runBlocking {
            val waiting = launch { lock.awaitTryAcquire() }
            yield()
            waiting.cancel()
        }
        acquiring.complete(term)
        released.isDone.assert().isTrue()
    }

    @Test
    fun `cancelled awaitAcquire calls leave no waiter on the client`() {
        val first = client()
        val second = client()
        val held = await { first.lock(name.value).awaitAcquire(Duration.ofSeconds(1)) }
        runBlocking {
            repeat(CANCELLED_ACQUIRES) {
                val waiting =
                    launch(Dispatchers.Default) { second.lock(name.value).awaitAcquire(Duration.ofMinutes(10)) }
                waiting.cancelAndJoin()
            }
        }
        settle()
        second.isIdle().assert().isTrue()
        held.close()
    }

    /**
     * The engine's future behind [term]'s `revoked` stage, read reflectively: the lock term wraps the engine term,
     * which keeps the future private.
     */
    private fun revokedFutureOf(term: LockTerm): CompletableFuture<*> {
        val engineTerm = term.javaClass.getDeclaredField("term").apply { isAccessible = true }.get(term)
        return engineTerm.javaClass.getDeclaredField("revokedFuture").apply { isAccessible = true }
            .get(engineTerm) as CompletableFuture<*>
    }

    @Test
    fun `SimbaEventFlow rejects a buffer capacity below one`() {
        runCatching { SimbaEventFlow(0) }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private class FakeTerm(override val revoked: CompletionStage<RevokeReason>) : Term {
        override val name = LeaseName("fake")
        override val token = 1L
        override val isValid = true

        override fun remaining(): Duration = Duration.ofSeconds(1)

        override fun ensureValid(minRemaining: Duration) = Unit
    }

    companion object {
        const val WAIT_SECONDS = 5L
        const val WAIT_MILLIS = WAIT_SECONDS * 1000
        const val CANCELLED_WAITS = 1_000
        const val CANCELLED_ACQUIRES = 200
    }
}
