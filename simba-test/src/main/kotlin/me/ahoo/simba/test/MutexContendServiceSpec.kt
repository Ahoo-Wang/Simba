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

package me.ahoo.simba.test

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.schedule.SimbaScheduler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

abstract class MutexContendServiceSpec {
    companion object {
        private val log = LoggerFactory.getLogger(MutexContendServiceSpec::class.java)
        const val START_MUTEX = "start"
        const val RESTART_MUTEX = "restart"
        const val GUARD_MUTEX = "guard"
        const val MULTI_CONTEND_MUTEX = "multiContend"
        const val SCHEDULE_MUTEX = "schedule"
        const val SIMBA_SCHEDULER_MUTEX = "simbaScheduler"
        const val OBSERVER_MUTEX = "observer"
    }

    abstract val mutexContendServiceFactory: MutexContendServiceFactory

    /**
     * A factory of this backend reporting to [observer]; `null` skips [observer].
     */
    open fun createObservedFactory(observer: ContendObserver): MutexContendServiceFactory? = null

    /**
     * Whether the backend reports [ContendObserver.onContend] (lease backends do; Zookeeper does not).
     */
    open val reportsContention: Boolean = true

    @Test
    open fun start() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            START_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                releasedFuture.complete(mutexState.after)
            }
        })
        contendService.start()
        acquiredFuture.join()
        contendService.isOwner.assert().isTrue()
        contendService.stop()
        releasedFuture.join()
        contendService.isOwner.assert().isFalse()
    }

    @Test
    open fun restart() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val acquiredFuture2 = CompletableFuture<MutexOwner>()
        val releasedFuture2 = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            RESTART_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                if (!acquiredFuture.isDone) {
                    acquiredFuture.complete(mutexState.after)
                } else {
                    acquiredFuture2.complete(mutexState.after)
                }
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                if (!releasedFuture.isDone) {
                    releasedFuture.complete(mutexState.after)
                } else {
                    releasedFuture2.complete(mutexState.after)
                }
            }
        })
        contendService.start()
        acquiredFuture.join()
        contendService.isOwner.assert().isTrue()
        contendService.stop()
        releasedFuture.join()
        contendService.isOwner.assert().isFalse()
        contendService.start()
        acquiredFuture2.join()
        contendService.isOwner.assert().isTrue()
        contendService.stop()
        releasedFuture2.join()
        contendService.isOwner.assert().isFalse()
    }

    @Test
    open fun guard() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            GUARD_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                releasedFuture.complete(mutexState.after)
            }
        })
        contendService.start()
        acquiredFuture.join()
        contendService.isOwner.assert().isTrue()
        TimeUnit.SECONDS.sleep(3)
        contendService.afterOwner.ownerId.assert().isEqualTo(contendService.contender.contenderId)
        contendService.isOwner.assert().isTrue()
        contendService.stop()
        releasedFuture.join()
        contendService.isOwner.assert().isFalse()
    }

    @Test
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    open fun multiContend() {
        val count = AtomicInteger(0)
        val invariantViolation = AtomicReference<AssertionError>()
        val currentOwnerIdRef = AtomicReference<String>()
        val contendServiceList: MutableList<MutexContendService> = ArrayList(10)
        var testFailure: Throwable? = null
        try {
            repeat(10) {
                val contendService =
                    mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
                        MULTI_CONTEND_MUTEX
                    ) {
                        override fun onAcquired(mutexState: MutexState) {
                            currentOwnerIdRef.set(mutexState.after.ownerId)
                            super.onAcquired(mutexState)
                            val ownerCount = count.incrementAndGet()
                            if (ownerCount != 1) {
                                invariantViolation.compareAndSet(
                                    null,
                                    AssertionError("Expected exactly one owner after acquire, but was $ownerCount.")
                                )
                            }
                        }

                        override fun onReleased(mutexState: MutexState) {
                            super.onReleased(mutexState)
                            val ownerCount = count.decrementAndGet()
                            if (ownerCount != 0) {
                                invariantViolation.compareAndSet(
                                    null,
                                    AssertionError("Expected no owner after release, but was $ownerCount.")
                                )
                            }
                        }
                    })
                contendService.start()
                contendServiceList.add(contendService)
            }
            TimeUnit.SECONDS.sleep(30)
            invariantViolation.get()?.let { throw it }
            count.get().assert().isEqualTo(1)
            val currentOwnerId = currentOwnerIdRef.get()
            for (contendService in contendServiceList) {
                if (contendService.afterOwner.ownerId.isNotBlank()) {
                    contendService.afterOwner.ownerId.assert().isEqualTo(currentOwnerId)
                }
            }
            val ownerCount = contendServiceList.count { it.contenderId == currentOwnerId }
            ownerCount.assert().isEqualTo(1)
        } catch (error: Throwable) {
            testFailure = error
        }
        var cleanupFailure: Throwable? = null
        for (contendService in contendServiceList.asReversed()) {
            try {
                if (contendService.running) contendService.stop()
            } catch (error: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = error
                } else {
                    cleanupFailure.addSuppressed(error)
                }
            }
        }
        cleanupFailure?.let { cleanupError -> testFailure?.addSuppressed(cleanupError) }
        testFailure?.let { throw it }
        cleanupFailure?.let { throw it }
    }

    @Test
    fun schedule() {
        val countDownLatch = CountDownLatch(1)
        val config = ScheduleConfig.delay(Duration.ZERO, Duration.ofSeconds(1))
        val worker = "Test Worker"
        val testScheduler = object : AbstractScheduler(SCHEDULE_MUTEX, mutexContendServiceFactory) {
            override val config: ScheduleConfig
                get() = config
            override val worker: String
                get() = worker

            override fun work() {
                countDownLatch.countDown()
            }
        }
        testScheduler.running.assert().isFalse()
        testScheduler.start()
        testScheduler.running.assert().isTrue()
        // A failure detector, not a performance bound: backends wait their initialDelay before contending, and
        // shared CI runners add latency. await() returns as soon as work() runs.
        countDownLatch.await(30, TimeUnit.SECONDS).assert().isTrue()
        testScheduler.stop()
        testScheduler.running.assert().isFalse()
    }

    @Test
    open fun simbaScheduler() {
        val context = CompletableFuture<Pair<String, Boolean>>()
        val scheduler = SimbaScheduler(
            SIMBA_SCHEDULER_MUTEX,
            mutexContendServiceFactory,
            ScheduleConfig.delay(Duration.ZERO, Duration.ofSeconds(1))
        ) {
            context.complete(it.mutex to (it.fencingToken >= MutexOwner.NO_FENCING_TOKEN))
        }
        scheduler.start()
        // A failure detector, not a performance bound (see schedule()).
        context.get(30, TimeUnit.SECONDS).assert().isEqualTo(SIMBA_SCHEDULER_MUTEX to true)
        scheduler.isLeader.assert().isTrue()
        scheduler.stop()
        scheduler.running.assert().isFalse()
    }

    @Test
    open fun observer() {
        val events = LinkedBlockingQueue<String>()
        val observer = object : ContendObserver {
            override fun onStarted(service: MutexContendService) {
                events.add("started:${service.mutex}")
            }

            override fun onStopped(service: MutexContendService) {
                events.add("stopped:${service.mutex}")
            }

            override fun onContend(mutex: String, renew: Boolean, durationNanos: Long, outcome: ContendOutcome) {
                events.add("contend:$outcome")
            }

            override fun onAcquired(mutex: String) {
                events.add("acquired:$mutex")
            }

            override fun onReleased(mutex: String) {
                events.add("released:$mutex")
            }
        }
        val factory = createObservedFactory(observer)
        Assumptions.assumeTrue(factory != null, "backend test does not provide an observed factory")
        try {
            val acquired = CountDownLatch(1)
            val contender = object : AbstractMutexContender(OBSERVER_MUTEX) {
                override fun onAcquired(mutexState: MutexState) {
                    acquired.countDown()
                }
            }
            val contendService = factory!!.createMutexContendService(contender)
            contendService.start()
            // A failure detector, not a performance bound (see schedule()).
            acquired.await(30, TimeUnit.SECONDS).assert().isTrue()
            contendService.stop()

            val recorded = events.toList()
            // An acquisition may be reported before start() returns, so only the order of each pair is fixed.
            recorded.filter { it.startsWith("acquired:") || it.startsWith("released:") }.assert()
                .containsExactly("acquired:$OBSERVER_MUTEX", "released:$OBSERVER_MUTEX")
            recorded.filter { it.startsWith("started:") }.assert().containsExactly("started:$OBSERVER_MUTEX")
            recorded.last().assert().isEqualTo("stopped:$OBSERVER_MUTEX")
            if (reportsContention) {
                recorded.assert().contains("contend:${ContendOutcome.OWNER}")
            } else {
                recorded.none { it.startsWith("contend:") }.assert().isTrue()
            }
        } finally {
            (factory as? AutoCloseable)?.close()
        }
    }
}
