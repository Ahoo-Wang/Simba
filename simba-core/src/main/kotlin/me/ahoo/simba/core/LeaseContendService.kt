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
package me.ahoo.simba.core

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Polling lease contention loop shared by lease-based backends.
 *
 * Owns scheduling, renewal, failure revocation and compensation of acquisitions that complete after their
 * lifecycle ended; the backend only implements [MutexLeaseStore].
 *
 * - [scheduler] only triggers contention and the lease watchdog, and must never block.
 * - [ioExecutor] runs [MutexLeaseStore] calls; at most one call per service is in flight.
 *
 * Local lease guard: after each successful acquisition or renewal, a watchdog is armed at the lease end
 * (`transitionAt`, measured from when the call was sent). If no renewal succeeds by then — including when the
 * backend call hangs — local ownership is revoked. A failed renewal keeps ownership while the lease is still
 * valid and retries with a halving backoff.
 *
 * @author ahoo wang
 */
open class LeaseContendService @JvmOverloads constructor(
    contender: MutexContender,
    handleExecutor: Executor,
    private val leaseStore: MutexLeaseStore,
    val leaseConfig: LeaseConfig,
    private val scheduler: ScheduledExecutorService,
    private val ioExecutor: Executor = DIRECT_EXECUTOR,
    observer: ContendObserver = ContendObserver.NOOP
) : AbstractMutexContendService(contender, handleExecutor, observer) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val DIRECT_EXECUTOR = Executor { it.run() }
        private const val MIN_RETRY_MILLIS = 100L

        /**
         * Remaining leases at least this long (e.g. `Long.MAX_VALUE` timestamps) are treated as unbounded.
         */
        private val UNBOUNDED_LEASE_NANOS = Long.MAX_VALUE / 2
    }

    private val contendPeriod: ContendPeriod = ContendPeriod(contenderId)

    /**
     * Guards scheduling state and serializes compensation with lifecycle transitions.
     */
    private val lock = Any()
    private var scheduleToken = 0L
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var inFlight = false
    private var contendRequested = false
    private var watchdogToken = 0L
    private var watchdogFuture: ScheduledFuture<*>? = null

    /**
     * Whether the last store reply granted this contender the lease. Decides acquire vs. renew from what the
     * backend said, not from [isOwner], which notifications update asynchronously.
     */
    private var holdsLease = false

    /**
     * `System.nanoTime()` at which the held lease ends; `null` when no bounded lease is held.
     */
    private var leaseDeadlineNanos: Long? = null

    /**
     * Called while starting, before the first contention is scheduled.
     */
    protected open fun onStart() = Unit

    /**
     * Called while stopping, before the lease is released.
     */
    protected open fun onStop() = Unit

    @Suppress("TooGenericExceptionCaught")
    final override fun startContend() {
        synchronized(lock) {
            // A contention of the previous lifecycle may still be in flight; it no longer blocks this one.
            inFlight = false
            contendRequested = false
            dropLease()
            onStart()
            try {
                schedule(leaseConfig.initialDelayMillis, currentGeneration, rethrowRejection = true)
            } catch (error: Throwable) {
                try {
                    onStop()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }
    }

    final override fun stopContend() {
        synchronized(lock) {
            cancelSchedule()
            dropLease()
            try {
                onStop()
            } finally {
                leaseStore.release(mutex, contenderId)
            }
        }
    }

    /**
     * Requests an immediate contention, e.g. when a backend event reports the lease was released.
     * Coalesced with an in-flight contention, which then reschedules immediately.
     */
    protected fun contendNow() {
        synchronized(lock) {
            val generation = currentGeneration
            if (!isActive(generation)) {
                return
            }
            if (inFlight) {
                contendRequested = true
                return
            }
            schedule(0, generation)
        }
    }

    private fun isActive(generation: Long): Boolean {
        return status.isActive && generation == currentGeneration
    }

    /**
     * A rejection (the scheduler was shut down, e.g. its factory closed before this service stopped) is logged,
     * except while starting, where it must fail [start].
     */
    private fun schedule(delay: Long, generation: Long, rethrowRejection: Boolean = false) {
        synchronized(lock) {
            if (!isActive(generation)) {
                /*
                 * A contention can still be in flight when stop() runs (backend calls are not interruptible);
                 * scheduling on from that path would leak work into a stopped or restarted lifecycle.
                 */
                log.debug {
                    "schedule - ignore - mutex:[$mutex] contenderId:[$contenderId] is not active[$status]."
                }
                return
            }
            log.debug {
                "schedule - mutex:[$mutex] contenderId:[$contenderId] - delay:[${delay}ms]."
            }
            cancelSchedule()
            val token = scheduleToken
            try {
                scheduledFuture = scheduler.schedule(
                    Runnable { dispatch(generation, token) },
                    delay,
                    TimeUnit.MILLISECONDS
                )
            } catch (error: RejectedExecutionException) {
                if (rethrowRejection) {
                    throw error
                }
                log.error(error) {
                    "schedule - mutex:[$mutex] contenderId:[$contenderId] - scheduler rejected, contention stops."
                }
            }
        }
    }

    private fun cancelSchedule() {
        scheduleToken++
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    private fun dispatch(generation: Long, token: Long) {
        synchronized(lock) {
            if (!isActive(generation) || token != scheduleToken || inFlight) {
                return
            }
            scheduledFuture = null
            inFlight = true
            contendRequested = false
        }
        try {
            ioExecutor.execute { contend(generation) }
        } catch (error: RejectedExecutionException) {
            log.error(error) { "dispatch - mutex:[$mutex] contenderId:[$contenderId] - rejected." }
            complete(generation, leaseConfig.ttlMillis)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun contend(generation: Long) {
        var nextDelay = leaseConfig.ttlMillis
        val renew = synchronized(lock) { holdsLease }
        val sentAtNanos = System.nanoTime()
        try {
            val mutexOwner = leaseStore.contend(mutex, contenderId, renew, leaseConfig)
            val outcome = if (mutexOwner.isOwner(contenderId)) ContendOutcome.OWNER else ContendOutcome.OTHER
            observe { onContend(mutex, renew, System.nanoTime() - sentAtNanos, outcome) }
            log.debug {
                "contend - mutex:[$mutex] contenderId:[$contenderId] - owner:[${mutexOwner.ownerId}]."
            }
            if (adopt(generation, mutexOwner, sentAtNanos)) {
                nextDelay = contendPeriod.ensureNextDelay(mutexOwner)
            }
        } catch (throwable: Throwable) {
            observe { onContend(mutex, renew, System.nanoTime() - sentAtNanos, ContendOutcome.FAILED) }
            log.error(throwable) {
                "contend - mutex:[$mutex] contenderId:[$contenderId] - failed:[${throwable.message}]."
            }
            nextDelay = onFailure(generation)
        } finally {
            complete(generation, nextDelay)
        }
    }

    /**
     * Ends the in-flight contention of [generation] and schedules the next one; a no-op for a stale lifecycle.
     */
    private fun complete(generation: Long, nextDelay: Long) {
        synchronized(lock) {
            if (generation != currentGeneration) {
                return
            }
            inFlight = false
            val delay = if (contendRequested) 0 else nextDelay
            contendRequested = false
            schedule(delay, generation)
        }
    }

    /**
     * Applies [mutexOwner] to the lifecycle that requested it. An acquisition that completes after its lifecycle
     * ended is released, unless a restarted lifecycle (same contenderId) is active and now relies on that lease.
     */
    private fun adopt(generation: Long, mutexOwner: MutexOwner, sentAtNanos: Long): Boolean {
        synchronized(lock) {
            if (isActive(generation)) {
                notifyOwner(mutexOwner)
                if (mutexOwner.isOwner(contenderId)) {
                    holdsLease = true
                    armWatchdog(generation, mutexOwner, sentAtNanos)
                } else {
                    dropLease()
                }
                return true
            }
            val restarted = status.isActive && generation != currentGeneration
            if (mutexOwner.isOwner(contenderId) && !restarted) {
                leaseStore.release(mutex, contenderId)
            }
            return false
        }
    }

    /**
     * Keeps ownership while the held lease is still valid and returns the retry delay; otherwise revokes local
     * ownership and retries after `ttl`.
     */
    private fun onFailure(generation: Long): Long {
        synchronized(lock) {
            if (!isActive(generation)) {
                return leaseConfig.ttlMillis
            }
            val remainingMillis = leaseDeadlineNanos?.let {
                TimeUnit.NANOSECONDS.toMillis(it - System.nanoTime())
            } ?: 0
            if (remainingMillis > 0) {
                return (remainingMillis / 2).coerceIn(MIN_RETRY_MILLIS, leaseConfig.ttlMillis)
            }
            val held = holdsLease
            dropLease()
            if (held || isOwner) {
                notifyOwner(MutexOwner.NONE)
            }
            return leaseConfig.ttlMillis
        }
    }

    private fun armWatchdog(generation: Long, mutexOwner: MutexOwner, sentAtNanos: Long) {
        disarmWatchdog()
        val remainingMillis = (mutexOwner.transitionAt - mutexOwner.currentAt).coerceAtLeast(0)
        val remainingNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis)
        if (remainingNanos >= UNBOUNDED_LEASE_NANOS) {
            return
        }
        val deadlineNanos = sentAtNanos + remainingNanos
        leaseDeadlineNanos = deadlineNanos
        val token = watchdogToken
        try {
            watchdogFuture = scheduler.schedule(
                Runnable { onLeaseExpired(generation, token) },
                (deadlineNanos - System.nanoTime()).coerceAtLeast(0),
                TimeUnit.NANOSECONDS
            )
        } catch (error: RejectedExecutionException) {
            log.error(error) { "armWatchdog - mutex:[$mutex] contenderId:[$contenderId] - scheduler rejected." }
        }
    }

    private fun disarmWatchdog() {
        watchdogToken++
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        leaseDeadlineNanos = null
    }

    private fun dropLease() {
        holdsLease = false
        disarmWatchdog()
    }

    private fun onLeaseExpired(generation: Long, token: Long) {
        synchronized(lock) {
            if (!isActive(generation) || token != watchdogToken) {
                return
            }
            watchdogFuture = null
            leaseDeadlineNanos = null
            holdsLease = false
            log.warn {
                "onLeaseExpired - mutex:[$mutex] contenderId:[$contenderId] - lease ended without renewal, revoking."
            }
            observe { onLeaseExpired(mutex) }
            /*
             * Not gated on isOwner: the acquisition notification may still be queued, and the sequential
             * notifier applies this release after it; a release while not owner is a no-op.
             */
            notifyOwner(MutexOwner.NONE)
        }
    }
}
