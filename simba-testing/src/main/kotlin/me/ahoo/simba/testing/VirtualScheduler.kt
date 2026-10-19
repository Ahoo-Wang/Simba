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

package me.ahoo.simba.testing

import me.ahoo.simba.engine.Scheduler
import java.time.Duration
import java.util.PriorityQueue

/**
 * Virtual time for a threaded `SimbaClient`: a [Scheduler] over [clock] whose timers only fire in [advance]. Give it to
 * `SimbaClient.builder().scheduler(...)` together with a direct I/O and callback executor, and the engines settle
 * deterministically between timers. Thread-safe; tasks run on the thread calling [advance].
 */
class VirtualScheduler(val clock: VirtualClock = VirtualClock()) : Scheduler {
    private class Task(val at: Long, val seq: Long, val task: Runnable)

    private val lock = Any()
    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L

    override fun nanoTime(): Long = clock.nanoTime()

    /** Queues [task]; a task already due runs on the next [advance], even one of zero. */
    override fun schedule(at: Long, task: Runnable) {
        synchronized(lock) {
            tasks += Task(at, seq++, task)
        }
    }

    /** Timers not fired yet. */
    val pending: Int get() = synchronized(lock) { tasks.size }

    /**
     * Moves time forward by [duration], running every due task at its own time in order and calling [settle] before
     * each task and at the end, so that what a task triggers is done before time moves on.
     */
    fun advance(duration: Duration, settle: () -> Unit = {}) {
        require(!duration.isNegative) { "Time never moves backwards." }
        val target = clock.nanoTime() + duration.toNanos()
        while (true) {
            settle()
            val next = synchronized(lock) {
                tasks.peek()?.takeIf { it.at - target <= 0 }?.also { tasks.poll() }
            }
            if (next == null) {
                if (clock.nanoTime() - target < 0) {
                    clock.advanceTo(target)
                    continue
                }
                return
            }
            if (next.at - clock.nanoTime() > 0) {
                clock.advanceTo(next.at)
            }
            next.task.run()
        }
    }
}
