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

import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.FakeMutexContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.RecordingObserver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SimbaSchedulerTest {
    private val factory = CapturingFactory()

    @Test
    fun `work runs on acquisition with the term's fencing token`() {
        val contexts = LinkedBlockingQueue<Pair<String, Long>>()
        val scheduler = SimbaScheduler("report", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1))) {
            contexts.add(it.mutex to it.fencingToken)
        }
        scheduler.start()

        factory.lead(scheduler, fencingToken = 7)

        contexts.poll(2, TimeUnit.SECONDS).assert().isEqualTo("report" to 7L)
        scheduler.isLeader.assert().isTrue()
        scheduler.fencingToken.assert().isEqualTo(7)
        scheduler.stop()
    }

    @Test
    fun `losing leadership interrupts running work`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val scheduler = SimbaScheduler("lose", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1))) {
            started.countDown()
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
        }
        scheduler.start()
        factory.lead(scheduler)
        started.await(2, TimeUnit.SECONDS).assert().isTrue()

        factory.service!!.publishOwner(MutexOwner("other")).join()

        interrupted.await(2, TimeUnit.SECONDS).assert().isTrue()
        scheduler.isLeader.assert().isFalse()
        scheduler.stop()
    }

    @Test
    fun `work interrupted by leadership loss runs again on the next term`() {
        val starts = LinkedBlockingQueue<Long>()
        val scheduler = SimbaScheduler("again", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1))) {
            starts.add(it.fencingToken)
            Thread.sleep(TimeUnit.MINUTES.toMillis(1))
        }
        scheduler.start()
        factory.lead(scheduler, fencingToken = 1)
        starts.poll(2, TimeUnit.SECONDS).assert().isEqualTo(1L)

        factory.service!!.publishOwner(MutexOwner("other")).join()
        factory.lead(scheduler, fencingToken = 2)

        starts.poll(2, TimeUnit.SECONDS).assert().isEqualTo(2L)
        scheduler.stop()
    }

    @Test
    fun `work outcomes reach the contend service observer`() {
        val observer = RecordingObserver()
        val observedFactory = CapturingFactory(observer)
        val runs = AtomicInteger()
        val sleeping = CountDownLatch(1)
        val scheduler = SimbaScheduler(
            "observed",
            observedFactory,
            ScheduleConfig.rate(Duration.ZERO, Duration.ofMillis(20))
        ) {
            when (runs.incrementAndGet()) {
                1 -> Unit
                2 -> error("second run fails")
                else -> {
                    sleeping.countDown()
                    Thread.sleep(TimeUnit.MINUTES.toMillis(1))
                }
            }
        }
        scheduler.start()
        observedFactory.lead(scheduler)
        val events = generateSequence { observer.events.poll(2, TimeUnit.SECONDS) }.take(3).toList()
        sleeping.await(2, TimeUnit.SECONDS).assert().isTrue()

        observedFactory.service!!.publishOwner(MutexOwner("other")).join()

        events.assert().containsExactly("acquired:observed", "work:observed:SUCCESS", "work:observed:FAILED")
        observer.events.poll(2, TimeUnit.SECONDS).assert().isEqualTo("released:observed")
        observer.events.poll(2, TimeUnit.SECONDS).assert().isEqualTo("work:observed:INTERRUPTED")
        scheduler.stop()
    }

    @Test
    fun `a failing run does not stop later runs`() {
        val runs = AtomicInteger()
        val secondRun = CountDownLatch(1)
        val scheduler = SimbaScheduler("retry", factory, ScheduleConfig.rate(Duration.ZERO, Duration.ofMillis(20))) {
            if (runs.incrementAndGet() == 1) error("first run fails")
            secondRun.countDown()
        }
        scheduler.start()
        factory.lead(scheduler)

        secondRun.await(2, TimeUnit.SECONDS).assert().isTrue()
        scheduler.stop()
    }

    @Test
    fun `stop shuts the worker thread down and restart recreates it`() {
        val runs = LinkedBlockingQueue<String>()
        val scheduler = SimbaScheduler(
            "restart",
            factory,
            ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1)),
            worker = "restart-worker"
        ) { runs.add(Thread.currentThread().name) }
        scheduler.start()
        factory.lead(scheduler)
        runs.poll(2, TimeUnit.SECONDS)!!.assert().startsWith("restart-worker-")

        scheduler.stop()
        scheduler.running.assert().isFalse()
        scheduler.start()
        factory.lead(scheduler)

        runs.poll(2, TimeUnit.SECONDS)!!.assert().startsWith("restart-worker-")
        scheduler.stop()
    }

    @Test
    fun `java constructor names the worker after the mutex and close is idempotent`() {
        val ran = LinkedBlockingQueue<String>()
        val scheduler = SimbaScheduler(
            "java-style",
            factory,
            ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1)),
            ScheduledWork { ran.add(Thread.currentThread().name) }
        )
        scheduler.worker.assert().isEqualTo("java-style")
        scheduler.start()
        factory.lead(scheduler)
        ran.poll(2, TimeUnit.SECONDS)!!.assert().startsWith("java-style-")

        scheduler.close()
        scheduler.close()

        scheduler.running.assert().isFalse()
    }

    private class CapturingFactory(private val observer: ContendObserver = ContendObserver.NOOP) :
        MutexContendServiceFactory {
        var service: FakeMutexContendService? = null

        override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
            return FakeMutexContendService(mutexContender, observer = observer).also { service = it }
        }

        fun lead(scheduler: SimbaScheduler, fencingToken: Long = MutexOwner.NO_FENCING_TOKEN) {
            val service = service!!
            check(service.mutex == scheduler.mutex)
            service.publishOwner(MutexOwner(service.contenderId, fencingToken = fencingToken)).join()
        }
    }
}
