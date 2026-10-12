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
package me.ahoo.simba.schedule

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.WorkOutcome
import me.ahoo.simba.util.Threads.defaultFactory
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Runs scheduled work while its contender leads: [start] on acquisition, [cancel] (interrupting) on release,
 * [shutdown] when the scheduler stops. The executor is created on first acquisition, so a node that never leads,
 * or a stopped scheduler, holds no threads. Callbacks are serialized by the contend service.
 */
internal class ScheduledWorkRunner(
    private val mutex: String,
    private val config: () -> ScheduleConfig,
    private val worker: () -> String,
    private val observer: () -> ContendObserver,
    private val work: () -> Unit
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * Longest [handOver] wait when the lease is unbounded (Zookeeper).
         */
        val MAX_UNBOUNDED_HANDOVER_WAIT: Duration = Duration.ofSeconds(30)

        /**
         * The observer of the contend service the scheduler runs on, so work events go where contention events go.
         */
        fun observerOf(contendService: MutexContendService): ContendObserver {
            return (contendService as? AbstractMutexContendService)?.observer ?: ContendObserver.NOOP
        }
    }

    @Volatile
    private var executor: ScheduledThreadPoolExecutor? = null

    @Volatile
    private var workFuture: ScheduledFuture<*>? = null

    /**
     * Held by the worker thread while a run is in progress.
     */
    private val running = ReentrantLock()

    fun start() {
        if (workFuture?.isDone == false) {
            return
        }
        val schedule = config()
        val initialDelay = schedule.initialDelay.toMillis()
        val period = schedule.period.toMillis()
        val executor = ensureExecutor()
        workFuture = if (ScheduleConfig.Strategy.FIXED_RATE == schedule.strategy) {
            executor.scheduleAtFixedRate(::safeWork, initialDelay, period, TimeUnit.MILLISECONDS)
        } else {
            executor.scheduleWithFixedDelay(::safeWork, initialDelay, period, TimeUnit.MILLISECONDS)
        }
    }

    fun cancel() {
        workFuture?.cancel(true)
    }

    /**
     * Cancels the work and waits until a running run returned, at most for the lease the [contendService] still holds
     * ([MAX_UNBOUNDED_HANDOVER_WAIT] when unbounded), so the lease is released only after the work stopped (ADR 0008).
     * Returns whether the work stopped in time. From a run itself it returns at once.
     */
    fun handOver(contendService: MutexContendService): Boolean {
        cancel()
        val remaining = contendService.leaseRemaining
        val wait = if (remaining == MutexContendService.UNBOUNDED_LEASE) MAX_UNBOUNDED_HANDOVER_WAIT else remaining
        if (awaitIdle(wait)) {
            return true
        }
        log.warn {
            "handOver - mutex:[$mutex] - work still running after [${wait.toMillis()}ms], releasing anyway."
        }
        return false
    }

    /**
     * Whether no run is in progress within [wait]; `false` when interrupted (the flag is restored).
     */
    private fun awaitIdle(wait: Duration): Boolean {
        try {
            if (running.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                try {
                    return true
                } finally {
                    running.unlock()
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return false
    }

    fun shutdown() {
        executor?.shutdown()
        executor = null
    }

    private fun ensureExecutor(): ScheduledThreadPoolExecutor {
        return executor ?: ScheduledThreadPoolExecutor(1, defaultFactory(worker())).also { executor = it }
    }

    private fun safeWork() {
        running.lock()
        try {
            runOnce()
        } finally {
            running.unlock()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runOnce() {
        val startedAtNanos = System.nanoTime()
        val outcome = try {
            work()
            WorkOutcome.SUCCESS
        } catch (interrupted: InterruptedException) {
            // Expected when leadership is lost or the scheduler stops: the run was cancelled, not failed.
            Thread.currentThread().interrupt()
            log.info { "work - mutex:[$mutex] - interrupted:[${interrupted.message}]." }
            WorkOutcome.INTERRUPTED
        } catch (throwable: Throwable) {
            log.error(throwable) { "work - mutex:[$mutex] - failed:[${throwable.message}]." }
            WorkOutcome.FAILED
        }
        try {
            observer().onWork(mutex, System.nanoTime() - startedAtNanos, outcome)
        } catch (error: Throwable) {
            log.warn(error) { "work - mutex:[$mutex] - observer failed." }
        }
    }
}
