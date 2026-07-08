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

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Factory exposing the created contender so tests can drive onAcquired/onReleased.
 */
private class CapturingFactory : MutexContendServiceFactory {
    var contender: MutexContender? = null
        private set

    override fun createMutexContendService(mutexContender: MutexContender): me.ahoo.simba.core.MutexContendService {
        contender = mutexContender
        // Return a no-op contend service; tests call contender.onAcquired directly
        return NoopContendService(mutexContender)
    }
}

private class NoopContendService(contender: MutexContender) :
    me.ahoo.simba.core.AbstractMutexContendService(contender, me.ahoo.simba.core.SameThreadExecutor) {
    override fun startContend() = Unit
    override fun stopContend() = Unit
}

/**
 * Builds an [AbstractScheduler] whose [AbstractScheduler.config] / [AbstractScheduler.worker] / [AbstractScheduler.work]
 * are supplied as already-initialized locals, avoiding the super-ctor init-order footgun.
 * Captures the [AbstractScheduler.WorkContender] created in the constructor so tests can drive it directly.
 *
 * Returns the pair (scheduler, contender). Mirrors the anonymous-object pattern used by
 * [MutexContendServiceSpec.schedule].
 */
private class SchedulerHandle(
    val scheduler: AbstractScheduler,
    val contender: AbstractScheduler.WorkContender
)

private fun newScheduler(
    mutex: String,
    config: ScheduleConfig,
    worker: String,
    latch: CountDownLatch,
    counter: AtomicInteger,
    shouldThrow: Boolean = false
): SchedulerHandle {
    val factory = CapturingFactory()
    // locals captured by the closure are fully initialized before the object is created,
    // so `config`/`worker` getters read non-null values during the super-ctor.
    val scheduler = object : AbstractScheduler(mutex, factory) {
        override val config: ScheduleConfig get() = config
        override val worker: String get() = worker
        override fun work() {
            // Advance the counter FIRST so the throw-once guard (firstRun) can ever
            // become false; otherwise the throwing run never increments and every
            // subsequent run would throw forever.
            val firstRun = counter.getAndIncrement() == 0
            if (shouldThrow && firstRun) {
                throw IllegalStateException("work-boom")
            }
            latch.countDown()
        }
    }
    val contender = factory.contender!! as AbstractScheduler.WorkContender
    return SchedulerHandle(scheduler, contender)
}

class AbstractSchedulerTest {
    @Test
    fun `onAcquired schedules work at fixed rate`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val handle = newScheduler(
            mutex = "m",
            config = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(50)),
            worker = "rate-worker",
            latch = latch,
            counter = counter
        )
        val contender = handle.contender

        try {
            handle.scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))

            assertThat("work should run at least once", latch.await(2, TimeUnit.SECONDS))
            assertThat(counter.get(), greaterThanOrEqualTo(1))
        } finally {
            // onReleased cancels the scheduled workFuture; NoopContendService.stop() alone
            // would not, leaving the non-daemon scheduler thread running.
            contender.onReleased(MutexState(MutexOwner(contender.contenderId, 0, 100, 200), MutexOwner.NONE))
            handle.scheduler.stop()
        }
    }

    @Test
    fun `onAcquired schedules work with fixed delay`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val handle = newScheduler(
            mutex = "m",
            config = ScheduleConfig.delay(Duration.ofMillis(0), Duration.ofMillis(50)),
            worker = "delay-worker",
            latch = latch,
            counter = counter
        )
        val contender = handle.contender

        try {
            handle.scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))

            assertThat("work should run at least once", latch.await(2, TimeUnit.SECONDS))
            assertThat(counter.get(), greaterThanOrEqualTo(1))
        } finally {
            contender.onReleased(MutexState(MutexOwner(contender.contenderId, 0, 100, 200), MutexOwner.NONE))
            handle.scheduler.stop()
        }
    }

    @Test
    fun `onReleased cancels scheduled work without throwing`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val handle = newScheduler(
            mutex = "m",
            config = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(50)),
            worker = "release-worker",
            latch = latch,
            counter = counter
        )
        val contender = handle.contender

        try {
            handle.scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))
            // cancel the scheduled future
            contender.onReleased(MutexState(MutexOwner(contender.contenderId, 0, 100, 200), MutexOwner.NONE))
        } finally {
            handle.scheduler.stop()
        }
    }

    @Test
    fun `safeWork swallows work exception and keeps scheduling`() {
        val latch = CountDownLatch(2)
        val counter = AtomicInteger(0)
        val handle = newScheduler(
            mutex = "m",
            config = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(20)),
            worker = "throw-worker",
            latch = latch,
            counter = counter,
            shouldThrow = true
        )
        val contender = handle.contender

        try {
            handle.scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))

            assertThat("work should continue after a thrown exception", latch.await(2, TimeUnit.SECONDS))
            assertThat(counter.get(), greaterThanOrEqualTo(2))
        } finally {
            contender.onReleased(MutexState(MutexOwner(contender.contenderId, 0, 100, 200), MutexOwner.NONE))
            handle.scheduler.stop()
        }
    }

    @Test
    fun `running reflects contend service status`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val handle = newScheduler(
            mutex = "m",
            config = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(50)),
            worker = "running-worker",
            latch = latch,
            counter = counter
        )

        assertThat(handle.scheduler.running, equalTo(false))
        handle.scheduler.start()
        assertThat(handle.scheduler.running, equalTo(true))
        handle.scheduler.stop()
        assertThat(handle.scheduler.running, equalTo(false))
    }
}
