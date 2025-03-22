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

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.util.Threads.defaultFactory
import org.slf4j.LoggerFactory
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
        private val log = LoggerFactory.getLogger(AbstractScheduler::class.java)
    }

    private val contendService: MutexContendService =
        contendServiceFactory.createMutexContendService(WorkContender(mutex))

    protected abstract val config: ScheduleConfig
    protected abstract val worker: String
    protected abstract fun work()
    fun start() {
        contendService.start()
    }

    fun stop() {
        contendService.stop()
    }

    val running: Boolean
        get() = contendService.running

    inner class WorkContender(mutex: String) : AbstractMutexContender(mutex) {
        private val scheduledThreadPoolExecutor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(
            1,
            defaultFactory(
                worker
            )
        )

        @Volatile
        private var workFuture: ScheduledFuture<*>? = null

        override fun onAcquired(mutexState: MutexState) {
            super.onAcquired(mutexState)
            if (workFuture == null || workFuture!!.isDone) {
                val initialDelay = config.initialDelay.toMillis()
                val period = config.period.toMillis()
                workFuture = if (ScheduleConfig.Strategy.FIXED_RATE == config.strategy) {
                    scheduledThreadPoolExecutor.scheduleAtFixedRate(
                        this::safeWork,
                        initialDelay,
                        period,
                        TimeUnit.MILLISECONDS
                    )
                } else {
                    scheduledThreadPoolExecutor.scheduleWithFixedDelay(
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

        @Suppress("TooGenericExceptionCaught")
        private fun safeWork() {
            try {
                work()
            } catch (throwable: Throwable) {
                if (log.isErrorEnabled) {
                    log.error(throwable.message, throwable)
                }
            }
        }
    }
}
