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
package me.ahoo.simba.jdbc

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexState
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit test for the stop/in-flight-task race. No database required:
 * [MutexOwnerRepository] is faked with a blocking implementation that mimics a
 * JDBC call (interrupt-insensitive).
 *
 * stopContend shuts the executor down without awaiting the in-flight task; a JDBC
 * call cannot be interrupted, so the task keeps running and its trailing
 * nextSchedule call throws RejectedExecutionException on the shut-down executor —
 * on the failure path even from inside the catch block. The thrown REE is
 * unobservable through the (cancelled) scheduled future, so the test swaps in a
 * recording executor subclass and asserts no scheduling is ATTEMPTED after stop.
 */
class JdbcMutexContendServiceStopTest {
    @Test
    fun `contend task completing after stop compensates acquisition without rescheduling`() {
        val invoked = CountDownLatch(1)
        val inFlight = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val compensatingRelease = CountDownLatch(1)
        val acquireCount = AtomicInteger()
        val ownerId = AtomicReference("")
        val repository = object : MutexOwnerRepository {
            override fun initMutex(mutex: String) = true
            override fun tryInitMutex(mutex: String) = true

            override fun getOwner(mutex: String): MutexOwnerEntity {
                throw NotFoundMutexOwnerException("not expected in this test")
            }

            override fun acquire(mutex: String, contenderId: String, ttl: Long, transition: Long) = false

            override fun acquireAndGetOwner(
                mutex: String,
                contenderId: String,
                ttl: Long,
                transition: Long
            ): MutexOwnerEntity {
                acquireCount.incrementAndGet()
                invoked.countDown()
                // interrupt-insensitive wait, like a JDBC call in flight
                while (inFlight.count > 0) {
                    try {
                        inFlight.await()
                    } catch (_: InterruptedException) {
                    }
                }
                ownerId.set(contenderId)
                completed.countDown()
                return MutexOwnerEntity(mutex, contenderId, 0, Long.MAX_VALUE, Long.MAX_VALUE)
            }

            override fun release(mutex: String, contenderId: String): Boolean {
                val released = ownerId.compareAndSet(contenderId, "")
                if (released) {
                    compensatingRelease.countDown()
                }
                return released
            }

            override fun ensureOwner(mutex: String): MutexOwnerEntity {
                throw NotFoundMutexOwnerException("not expected in this test")
            }
        }
        val contender = object : AbstractMutexContender("m") {
            override fun onAcquired(mutexState: MutexState) = Unit
            override fun onReleased(mutexState: MutexState) = Unit
        }
        val contendService = JdbcMutexContendService(
            contender,
            Executor { it.run() }, // synchronous handleExecutor: notifyOwner applies inline
            repository,
            Duration.ofMillis(300), // window to swap in the recording executor before the task fires
            Duration.ofSeconds(10),
            Duration.ofSeconds(6)
        )

        contendService.start()

        val recordingExecutor = RecordingScheduledExecutor()
        val executorField = JdbcMutexContendService::class.java.getDeclaredField("executorService")
        executorField.isAccessible = true
        executorField.set(contendService, recordingExecutor)

        assertThat("contend task should reach the repository", invoked.await(5, TimeUnit.SECONDS))

        contendService.stop()
        // stop shut the recording executor down; the in-flight task is still blocked
        assertThat(recordingExecutor.scheduleAttemptsWhenShutdown.get(), equalTo(0))

        inFlight.countDown()
        assertThat("in-flight acquire should complete", completed.await(2, TimeUnit.SECONDS))

        // the in-flight task finishes now: any (buggy) rescheduling attempt lands on the
        // shut-down recording executor within milliseconds
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (recordingExecutor.scheduleAttemptsWhenShutdown.get() == 0 &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(20)
        }
        assertThat(
            "nextSchedule must not attempt to schedule on the shut-down executor after stop",
            recordingExecutor.scheduleAttemptsWhenShutdown.get(),
            equalTo(0)
        )
        assertThat("no further contention should run after stop", acquireCount.get(), equalTo(1))
        assertThat(
            "an acquisition completing after stop must be compensated",
            compensatingRelease.await(1, TimeUnit.SECONDS),
            equalTo(true)
        )
        assertThat(ownerId.get(), equalTo(""))
    }

    private class RecordingScheduledExecutor : ScheduledThreadPoolExecutor(1) {
        val scheduleAttemptsWhenShutdown = AtomicInteger()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*>? {
            if (isShutdown) {
                scheduleAttemptsWhenShutdown.incrementAndGet()
            }
            return super.schedule(command, delay, unit)
        }
    }
}
