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
package me.ahoo.simba.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs submitted tasks one at a time, in submission order, on [delegate].
 *
 * A single drain loop processes queued tasks, so a direct delegate or a task submitting more work never recurses.
 *
 * @author ahoo wang
 */
internal class SequentialExecutor(private val delegate: Executor) : Executor {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val draining = AtomicBoolean(false)

    override fun execute(command: Runnable) {
        queue.add(command)
        try {
            scheduleDrain()
        } catch (error: RejectedExecutionException) {
            queue.remove(command)
            throw error
        }
    }

    private fun scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return
        }
        try {
            delegate.execute(::drain)
        } catch (error: RejectedExecutionException) {
            draining.set(false)
            throw error
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        try {
            while (true) {
                val task = queue.poll() ?: break
                try {
                    task.run()
                } catch (error: Throwable) {
                    log.error(error) { "drain - task failed." }
                }
            }
        } finally {
            draining.set(false)
            // A task submitted after the last poll but before the flag reset must not be stranded.
            if (queue.isNotEmpty()) {
                scheduleDrain()
            }
        }
    }
}
