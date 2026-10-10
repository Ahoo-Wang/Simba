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
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.util.Threads.defaultFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Abstract Scheduler.
 *
 * @author ahoo wang
 */
abstract class AbstractScheduler(
    val mutex: String,
    contendServiceFactory: MutexContendServiceFactory
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val workContender = WorkContender(mutex)
    private val contendService: MutexContendService =
        contendServiceFactory.createMutexContendService(workContender)

    protected abstract val config: ScheduleConfig
    protected abstract val worker: String
    protected abstract fun work()
    fun start() {
        contendService.start()
    }

    fun stop() {
        try {
            contendService.stop()
        } finally {
            workContender.shutdown()
        }
    }

    val running: Boolean
        get() = contendService.running

    /**
     * Fencing token of the current leadership term, for [work] to pass to protected resources.
     * [me.ahoo.simba.core.MutexOwner.NO_FENCING_TOKEN] when not leader or unsupported by the backend.
     */
    protected val fencingToken: Long
        get() = contendService.fencingToken

    inner class WorkContender(mutex: String) : AbstractMutexContender(mutex) {
        /**
         * Created lazily on first acquisition and shut down by [shutdown] on scheduler stop,
         * so a stopped (or never-started) scheduler holds no threads; a restart recreates it.
         */
        @Volatile
        private var scheduledThreadPoolExecutor: ScheduledThreadPoolExecutor? = null

        @Volatile
        private var workFuture: ScheduledFuture<*>? = null

        override fun onAcquired(mutexState: MutexState) {
            super.onAcquired(mutexState)
            if (workFuture == null || workFuture!!.isDone) {
                val initialDelay = config.initialDelay.toMillis()
                val period = config.period.toMillis()
                val executor = ensureExecutor()
                workFuture = if (ScheduleConfig.Strategy.FIXED_RATE == config.strategy) {
                    executor.scheduleAtFixedRate(
                        this::safeWork,
                        initialDelay,
                        period,
                        TimeUnit.MILLISECONDS
                    )
                } else {
                    executor.scheduleWithFixedDelay(
                        this::safeWork,
                        initialDelay,
                        period,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
        }

        override fun onReleased(mutexState: MutexState) {
            super.onReleased(mutexState)
            workFuture?.cancel(true)
        }

        private fun ensureExecutor(): ScheduledThreadPoolExecutor {
            return scheduledThreadPoolExecutor
                ?: ScheduledThreadPoolExecutor(1, defaultFactory(worker))
                    .also { scheduledThreadPoolExecutor = it }
        }

        fun shutdown() {
            scheduledThreadPoolExecutor?.shutdown()
            scheduledThreadPoolExecutor = null
        }

        @Suppress("TooGenericExceptionCaught")
        private fun safeWork() {
            try {
                work()
            } catch (throwable: Throwable) {
                log.error(throwable) { "work - mutex:[$mutex] - failed:[${throwable.message}]." }
            }
        }
    }
}
