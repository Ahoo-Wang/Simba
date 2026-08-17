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
import org.junit.jupiter.api.assertThrows
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
        val repository = BlockingMutexOwnerRepository()
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

        assertThat("contend task should reach the repository", repository.firstInvoked.await(5, TimeUnit.SECONDS))

        contendService.stop()
        // stop shut the recording executor down; the in-flight task is still blocked
        assertThat(recordingExecutor.scheduleAttemptsWhenShutdown.get(), equalTo(0))

        repository.unblockFirst.countDown()
        assertThat("in-flight acquire should complete", repository.firstCompleted.await(2, TimeUnit.SECONDS))

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
        assertThat("no further contention should run after stop", repository.acquireCount.get(), equalTo(1))
        assertThat(
            "an acquisition completing after stop must be compensated",
            repository.compensatingRelease.await(1, TimeUnit.SECONDS),
            equalTo(true)
        )
        assertThat(repository.ownerId.get(), equalTo(""))
    }

    @Test
    fun `stale acquisition does not release restarted lifecycle ownership`() {
        val repository = BlockingMutexOwnerRepository()
        val contender = object : AbstractMutexContender("m") {
            override fun onAcquired(mutexState: MutexState) = Unit
            override fun onReleased(mutexState: MutexState) = Unit
        }
        val contendService = JdbcMutexContendService(
            contender,
            Executor { it.run() },
            repository,
            Duration.ZERO,
            Duration.ofSeconds(10),
            Duration.ofSeconds(6)
        )

        contendService.start()
        assertThat("first acquire should be in flight", repository.firstInvoked.await(2, TimeUnit.SECONDS))
        val oldExecutor = executorOf(contendService)

        contendService.stop()
        contendService.start()
        assertThat("restarted lifecycle should acquire", repository.secondCompleted.await(2, TimeUnit.SECONDS))

        repository.unblockFirst.countDown()
        assertThat("stale task should finish", oldExecutor.awaitTermination(2, TimeUnit.SECONDS))

        assertThat(repository.releaseAttempts.get(), equalTo(1))
        assertThat(repository.ownerId.get(), equalTo(contender.contenderId))
        contendService.stop()
    }

    @Test
    fun `stale failure does not revoke restarted lifecycle ownership`() {
        val repository = BlockingMutexOwnerRepository()
        repository.firstFailure = IllegalStateException("stale database failure")
        val acquired = CountDownLatch(1)
        val released = AtomicInteger()
        val contender = object : AbstractMutexContender("m") {
            override fun onAcquired(mutexState: MutexState) {
                acquired.countDown()
            }

            override fun onReleased(mutexState: MutexState) {
                released.incrementAndGet()
            }
        }
        val contendService = JdbcMutexContendService(
            contender,
            Executor { it.run() },
            repository,
            Duration.ZERO,
            Duration.ofSeconds(10),
            Duration.ofSeconds(6)
        )

        contendService.start()
        assertThat("first acquire should be in flight", repository.firstInvoked.await(2, TimeUnit.SECONDS))
        val oldExecutor = executorOf(contendService)

        contendService.stop()
        contendService.start()
        assertThat("restarted lifecycle should acquire", acquired.await(2, TimeUnit.SECONDS))

        repository.unblockFirst.countDown()
        assertThat("stale task should finish", oldExecutor.awaitTermination(2, TimeUnit.SECONDS))

        assertThat(contendService.isOwner, equalTo(true))
        assertThat(released.get(), equalTo(0))
        contendService.stop()
    }

    @Test
    fun `restart failure rolls back lifecycle so stale acquisition is compensated`() {
        val repository = BlockingMutexOwnerRepository()
        val contender = object : AbstractMutexContender("m") {
            override fun onAcquired(mutexState: MutexState) = Unit
            override fun onReleased(mutexState: MutexState) = Unit
        }
        val contendService = JdbcMutexContendService(
            contender,
            Executor { it.run() },
            repository,
            Duration.ZERO,
            Duration.ofSeconds(10),
            Duration.ofSeconds(6)
        )

        contendService.start()
        assertThat("first acquire should be in flight", repository.firstInvoked.await(2, TimeUnit.SECONDS))
        val oldExecutor = executorOf(contendService)
        contendService.stop()

        setInitialDelay(contendService, Duration.ofSeconds(Long.MAX_VALUE))
        assertThrows<ArithmeticException> { contendService.start() }
        val failedExecutor = executorOf(contendService)

        repository.unblockFirst.countDown()
        assertThat("stale task should finish", oldExecutor.awaitTermination(2, TimeUnit.SECONDS))

        assertThat(failedExecutor.isShutdown, equalTo(true))
        assertThat(repository.compensatingRelease.await(1, TimeUnit.SECONDS), equalTo(true))
        assertThat(repository.ownerId.get(), equalTo(""))
    }

    private fun executorOf(contendService: JdbcMutexContendService): ScheduledThreadPoolExecutor {
        val executorField = JdbcMutexContendService::class.java.getDeclaredField("executorService")
        executorField.isAccessible = true
        return executorField.get(contendService) as ScheduledThreadPoolExecutor
    }

    private fun setInitialDelay(contendService: JdbcMutexContendService, initialDelay: Duration) {
        val initialDelayField = JdbcMutexContendService::class.java.getDeclaredField("initialDelay")
        initialDelayField.isAccessible = true
        initialDelayField.set(contendService, initialDelay)
    }

    private class BlockingMutexOwnerRepository : MutexOwnerRepository {
        var firstFailure: Throwable? = null
        val firstInvoked = CountDownLatch(1)
        val unblockFirst = CountDownLatch(1)
        val firstCompleted = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val compensatingRelease = CountDownLatch(1)
        val acquireCount = AtomicInteger()
        val releaseAttempts = AtomicInteger()
        val ownerId = AtomicReference("")

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
            val attempt = acquireCount.incrementAndGet()
            if (attempt == 1) {
                firstInvoked.countDown()
                while (unblockFirst.count > 0) {
                    try {
                        unblockFirst.await()
                    } catch (_: InterruptedException) {
                    }
                }
                firstFailure?.let { throw it }
            }
            ownerId.set(contenderId)
            if (attempt == 1) {
                firstCompleted.countDown()
            } else if (attempt == 2) {
                secondCompleted.countDown()
            }
            return MutexOwnerEntity(mutex, contenderId, 0, Long.MAX_VALUE, Long.MAX_VALUE)
        }

        override fun release(mutex: String, contenderId: String): Boolean {
            releaseAttempts.incrementAndGet()
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
