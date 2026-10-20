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

import me.ahoo.simba.spi.LeaseName
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * A lock on one lease name of a client session (ADR 0010 §6). The returned [LockTerm] is the credential: it is not
 * bound to a thread, and closing it releases the lease. Within one client a name has at most one holder; another
 * acquire of the same name waits until it is released.
 *
 * A held lock ends early when its term is revoked (session expired, deadline passed, taken over); it is then
 * released for good rather than re-acquired, so check [Term.isValid] or pass [Term.token] to the protected resource.
 *
 * [acquireAsync] and [tryAcquireAsync] wait without a thread. Cancelling the returned future
 * (`toCompletableFuture().cancel(...)`) abandons the attempt and releases a term it already won, so a cancelled
 * acquisition never leaves a lock held.
 */
interface DistributedLock {
    val name: LeaseName

    /**
     * Waits up to [timeout] (on the client's clock) for a term; throws [LockTimeoutException] when none was won in
     * time. Interrupting the caller abandons the attempt and rethrows the [InterruptedException]. A timeout too large
     * for nanoseconds waits as long as `Long.MAX_VALUE` nanoseconds. Throws [IllegalStateException] when the client is
     * closed, or when called on the client's event loop (it would block the loop).
     */
    @Throws(InterruptedException::class)
    fun acquire(timeout: Duration): LockTerm

    /**
     * One attempt: the term when this client wins the first answered attempt; `null` when another session or this
     * client holds the lease, or when no attempt is answered within one lease duration (backend unreachable).
     */
    @Throws(InterruptedException::class)
    fun tryAcquire(): LockTerm?

    /**
     * [acquire] without blocking: completes with the term, or exceptionally with [LockTimeoutException] when none was
     * won within [timeout], with [IllegalStateException] when the client closes first and with
     * [IllegalArgumentException] for a negative [timeout]; it never throws. The client's own locks wait without a
     * thread and complete on its callback executor, where `close()` in a dependent does not wait (as in a callback);
     * this default runs [acquire] on a virtual thread and interrupts it on cancellation.
     *
     * A callback executor that rejects tasks never delivers the `onElected` of a won term: as for any election, the
     * engine steps the lock's election down for one lease duration and the attempt never sees that term, so it keeps
     * waiting until its [timeout] (or until it wins a later term).
     */
    fun acquireAsync(timeout: Duration): CompletionStage<LockTerm> = acquireOnVirtualThread { acquire(timeout) }

    /** [tryAcquire] without blocking: completes with the term, or with `null` when this attempt won none. */
    fun tryAcquireAsync(): CompletionStage<LockTerm?> = acquireOnVirtualThread { tryAcquire() }
}

/**
 * Runs a blocking [acquire] on a new virtual thread; cancelling the result interrupts it, and a term it returns
 * after the result was cancelled is released.
 */
@JvmSynthetic
internal fun <T : LockTerm?> acquireOnVirtualThread(acquire: () -> T): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    val thread = Thread.ofVirtual().name("simba-lock-acquire").unstarted {
        @Suppress("TooGenericExceptionCaught")
        try {
            val term = acquire()
            if (!result.complete(term)) {
                term?.releaseAsync()
            }
        } catch (error: Throwable) {
            result.completeExceptionally(error)
        }
    }
    result.whenComplete { _, _ ->
        if (result.isCancelled) {
            thread.interrupt()
        }
    }
    thread.start()
    return result
}

/** A held [DistributedLock]: a [Term] that releases its lease when closed. */
interface LockTerm : Term, AutoCloseable {
    /** Revokes the term and releases the lease; idempotent. */
    fun releaseAsync(): CompletionStage<Void>

    /** [releaseAsync] and wait, except inside a leadership callback or leader work. */
    override fun close()
}

/** [DistributedLock.acquire] won no term within its timeout. */
class LockTimeoutException(val name: LeaseName, val timeout: Duration) :
    SimbaException("Lock [$name] was not acquired within [$timeout].")
