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
 * Runs [work] on the leader of [mutex] only, without subclassing: work starts when this node acquires the mutex
 * and is cancelled (interrupted) when it loses it.
 *
 * ```kotlin
 * SimbaScheduler("report", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofMinutes(1))) { context ->
 *     report.generate(fencingToken = context.fencingToken)
 * }.start()
 * ```
 *
 * [start] and [stop] follow the contend service lifecycle; [close] is idempotent.
 *
 * @param worker thread name prefix of the work executor.
 *
 * @author ahoo wang
 */
class SimbaScheduler(
    val mutex: String,
    contendServiceFactory: MutexContendServiceFactory,
    val config: ScheduleConfig,
    val worker: String = mutex,
    private val work: ScheduledWork
) : AutoCloseable {
    /**
     * Java-friendly constructor using [mutex] as the [worker] name.
     */
    constructor(
        mutex: String,
        contendServiceFactory: MutexContendServiceFactory,
        config: ScheduleConfig,
        work: ScheduledWork
    ) : this(mutex, contendServiceFactory, config, mutex, work)

    private val context = object : ScheduleContext {
        override val mutex: String
            get() = this@SimbaScheduler.mutex
        override val fencingToken: Long
            get() = this@SimbaScheduler.fencingToken
    }

    private val runner = ScheduledWorkRunner(mutex, { config }, { worker }) { work.work(context) }

    private val contendService: MutexContendService =
        contendServiceFactory.createMutexContendService(
            object : AbstractMutexContender(mutex) {
                override fun onAcquired(mutexState: MutexState) {
                    super.onAcquired(mutexState)
                    runner.start()
                }

                override fun onReleased(mutexState: MutexState) {
                    super.onReleased(mutexState)
                    runner.cancel()
                }
            }
        )

    val running: Boolean
        get() = contendService.running

    /**
     * Whether this node currently leads [mutex] and runs the work.
     */
    val isLeader: Boolean
        get() = contendService.isOwner

    /**
     * Fencing token of the current leadership term; [me.ahoo.simba.core.MutexOwner.NO_FENCING_TOKEN] when not leader.
     */
    val fencingToken: Long
        get() = contendService.fencingToken

    fun start() {
        contendService.start()
    }

    fun stop() {
        try {
            contendService.stop()
        } finally {
            runner.shutdown()
        }
    }

    /**
     * Idempotent: stops the scheduler when running and is a no-op otherwise.
     */
    override fun close() {
        try {
            contendService.close()
        } finally {
            runner.shutdown()
        }
    }
}
