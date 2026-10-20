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

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.ahoo.simba.DistributedLock
import me.ahoo.simba.LockTerm
import me.ahoo.simba.LockTimeoutException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Term
import java.time.Duration
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until a term of this lock is won within [timeout]; [DistributedLock.acquireAsync] without a waiting thread.
 * Throws [LockTimeoutException] when none was won in time. Cancelling the coroutine abandons the attempt and releases
 * a term it already won.
 */
suspend fun DistributedLock.awaitAcquire(timeout: Duration): LockTerm = acquireAsync(timeout).awaitTerm()

/** [DistributedLock.tryAcquireAsync] as a suspending call: the term, or `null` when this attempt won none. */
suspend fun DistributedLock.awaitTryAcquire(): LockTerm? = tryAcquireAsync().awaitTerm()

/**
 * Acquires the lock within [timeout], runs [block] with the term and releases it afterwards, also when [block]
 * throws or the coroutine is cancelled. The term may end before [block] returns: pass [Term.token] to the protected
 * resource or check [Term.isValid].
 */
suspend fun <R> DistributedLock.withLock(timeout: Duration, block: suspend (LockTerm) -> R): R {
    val term = awaitAcquire(timeout)
    try {
        return block(term)
    } finally {
        withContext(NonCancellable) {
            term.releaseAsync().awaitCompletion()
        }
    }
}

/**
 * Suspends until the term is revoked and returns why. Cancelling the coroutine stops the wait only; the term is not
 * affected, and the wait leaves nothing behind: every wait on a term shares one registration on [Term.revoked].
 */
suspend fun Term.awaitRevoked(): RevokeReason = relays.computeIfAbsent(revoked, ::RevocationRelay).await()

/** One relay per revocation stage, weakly keyed, so that waits on a term never pile up callbacks on its stage. */
private val relays: MutableMap<CompletionStage<RevokeReason>, RevocationRelay> =
    Collections.synchronizedMap(WeakHashMap())

/** Registers once on [stage] and resumes the waits still suspended on it; a cancelled wait leaves the relay. */
private class RevocationRelay(stage: CompletionStage<RevokeReason>) {
    private val waiters = LinkedHashSet<CancellableContinuation<RevokeReason>>()
    private var outcome: Result<RevokeReason>? = null

    init {
        stage.whenComplete { reason, error ->
            val result = if (error == null) Result.success(reason) else Result.failure(error.unwrap())
            val resumed = synchronized(this) {
                outcome = result
                waiters.toList().also { waiters.clear() }
            }
            resumed.forEach { it.resumeWith(result) }
        }
    }

    suspend fun await(): RevokeReason =
        suspendCancellableCoroutine { continuation ->
            val done = synchronized(this) { outcome ?: null.also { waiters += continuation } }
            if (done != null) {
                continuation.resumeWith(done)
            } else {
                continuation.invokeOnCancellation { synchronized(this) { waiters -= continuation } }
            }
        }
}

/** Awaits [this] without cancelling it when the coroutine is cancelled. */
private suspend fun <T> CompletionStage<T>.awaitCompletion(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error.unwrap())
            }
        }
    }

/**
 * Awaits a lock acquisition: cancelling the coroutine cancels the acquisition, and a term that arrives once the
 * coroutine was cancelled is released rather than lost.
 */
private suspend fun <T : LockTerm?> CompletionStage<T>.awaitTerm(): T {
    val future = toCompletableFuture()
    return suspendCancellableCoroutine { continuation ->
        future.whenComplete { term, error ->
            if (error == null) {
                continuation.resume(term) { _, value, _ -> value?.releaseAsync() }
            } else {
                continuation.resumeWithException(error.unwrap())
            }
        }
        continuation.invokeOnCancellation { future.cancel(false) }
    }
}

private fun Throwable.unwrap(): Throwable = if (this is CompletionException) cause ?: this else this
