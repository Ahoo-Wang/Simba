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

/**
 * Abstract Scheduler: leader-only scheduled work by subclassing. Prefer [SimbaScheduler], which needs no subclass
 * and passes a [ScheduleContext] to the work; both share the same scheduling semantics.
 *
 * @author ahoo wang
 */
abstract class AbstractScheduler(
    val mutex: String,
    contendServiceFactory: MutexContendServiceFactory
) {
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
        private val runner = ScheduledWorkRunner(mutex, { config }, { worker }) { work() }

        override fun onAcquired(mutexState: MutexState) {
            super.onAcquired(mutexState)
            runner.start()
        }

        override fun onReleased(mutexState: MutexState) {
            super.onReleased(mutexState)
            runner.cancel()
        }

        /**
         * Shuts the work executor down; a restart recreates it on the next acquisition.
         */
        fun shutdown() {
            runner.shutdown()
        }
    }
}
