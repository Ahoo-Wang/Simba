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
 * - [scheduler] only triggers contention and must never block.
 * - [ioExecutor] runs [MutexLeaseStore] calls; at most one call per service is in flight.
 *
 * @author ahoo wang
 */
open class LeaseContendService(
    contender: MutexContender,
    handleExecutor: Executor,
    private val leaseStore: MutexLeaseStore,
    val leaseConfig: LeaseConfig,
    private val scheduler: ScheduledExecutorService,
    private val ioExecutor: Executor = DIRECT_EXECUTOR
) : AbstractMutexContendService(contender, handleExecutor) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val DIRECT_EXECUTOR = Executor { it.run() }
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
            onStart()
            try {
                schedule(leaseConfig.initialDelayMillis, currentGeneration)
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

    private fun schedule(delay: Long, generation: Long) {
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
            scheduledFuture = scheduler.schedule(
                Runnable { dispatch(generation, token) },
                delay,
                TimeUnit.MILLISECONDS
            )
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
        try {
            val mutexOwner = leaseStore.contend(mutex, contenderId, isOwner, leaseConfig)
            log.debug {
                "contend - mutex:[$mutex] contenderId:[$contenderId] - owner:[${mutexOwner.ownerId}]."
            }
            if (adopt(generation, mutexOwner)) {
                nextDelay = contendPeriod.ensureNextDelay(mutexOwner)
            }
        } catch (throwable: Throwable) {
            log.error(throwable) {
                "contend - mutex:[$mutex] contenderId:[$contenderId] - failed:[${throwable.message}]."
            }
            revokeOnFailure(generation)
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
    private fun adopt(generation: Long, mutexOwner: MutexOwner): Boolean {
        synchronized(lock) {
            if (isActive(generation)) {
                notifyOwner(mutexOwner)
                return true
            }
            val restarted = status.isActive && generation != currentGeneration
            if (mutexOwner.isOwner(contenderId) && !restarted) {
                leaseStore.release(mutex, contenderId)
            }
            return false
        }
    }

    private fun revokeOnFailure(generation: Long) {
        synchronized(lock) {
            if (isActive(generation) && isOwner) {
                notifyOwner(MutexOwner.NONE)
            }
        }
    }
}
