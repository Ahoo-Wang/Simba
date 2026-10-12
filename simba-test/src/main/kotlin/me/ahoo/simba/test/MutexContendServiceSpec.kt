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
import me.ahoo.simba.core.LeaseExpiredException
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.partition.MemberRegistry
import me.ahoo.simba.partition.PartitionAssignment
import me.ahoo.simba.partition.SimbaPartitionedScheduler
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.schedule.SimbaScheduler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        const val LEASE_REMAINING_MUTEX = "leaseRemaining"
        const val RELINQUISH_MUTEX = "relinquish"
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

    /**
     * Cooldown of [relinquish]: long enough for another contender to take over first on this backend (JDBC
     * contenders only retry at the old lease end).
     */
    open val relinquishCooldown: Duration = Duration.ofSeconds(2)

    /**
     * This backend's [MemberRegistry]; `null` skips [partitionedScheduler].
     */
    open fun createMemberRegistry(): MemberRegistry? = null

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
    open fun leaseRemaining() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            LEASE_REMAINING_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                releasedFuture.complete(mutexState.after)
            }
        })
        contendService.leaseRemaining.assert().isEqualTo(Duration.ZERO)
        contendService.start()
        acquiredFuture.join()
        contendService.leaseRemaining.assert().isGreaterThan(Duration.ZERO)
        contendService.ensureLease()
        contendService.stop()
        releasedFuture.join()
        contendService.leaseRemaining.assert().isEqualTo(Duration.ZERO)
        assertThrows<LeaseExpiredException> { contendService.ensureLease() }
    }

    @Test
    open fun relinquish() {
        val firstAcquired = LinkedBlockingQueue<MutexOwner>()
        val first = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            RELINQUISH_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                firstAcquired.add(mutexState.after)
            }
        })
        val secondSawFirst = CountDownLatch(1)
        val secondAcquired = CompletableFuture<MutexOwner>()
        val second = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            RELINQUISH_MUTEX
        ) {
            override fun notifyOwner(mutexState: MutexState) {
                if (mutexState.after.isOwner(first.contenderId)) {
                    secondSawFirst.countDown()
                }
                super.notifyOwner(mutexState)
            }

            override fun onAcquired(mutexState: MutexState) {
                secondAcquired.complete(mutexState.after)
            }
        })
        first.start()
        firstAcquired.poll(30, TimeUnit.SECONDS).assert().isNotNull()
        second.start()
        if (reportsContention) {
            // Lease backends: make sure the second contender is scheduled against the first one's lease.
            secondSawFirst.await(30, TimeUnit.SECONDS).assert().isTrue()
        }

        first.relinquish(relinquishCooldown)

        first.isOwner.assert().isFalse()
        first.running.assert().isTrue()
        secondAcquired.get(30, TimeUnit.SECONDS).isOwner(second.contenderId).assert().isTrue()
        second.stop()
        // After the cooldown the first contender contends again and takes the released mutex back.
        firstAcquired.poll(30, TimeUnit.SECONDS).assert().isNotNull()
        first.isOwner.assert().isTrue()
        first.stop()
    }

    @Test
    open fun partitionedScheduler() {
        val registry = createMemberRegistry()
        Assumptions.assumeTrue(registry != null)
        val group = "partitioned-${System.nanoTime()}"
        val partitions = 4
        val runs = LinkedBlockingQueue<Pair<String, Int>>()
        val nodes = listOf("a", "b").map { member ->
            SimbaPartitionedScheduler(
                group = group,
                partitions = partitions,
                contendServiceFactory = mutexContendServiceFactory,
                memberRegistry = registry!!,
                config = ScheduleConfig.rate(Duration.ZERO, Duration.ofMillis(100)),
                membershipTtl = Duration.ofSeconds(3),
                memberId = member
            ) { runs.add(member to it.partition) }
        }

        /**
         * Waits until every partition ran on its owner among [members]; runs of earlier views are skipped.
         */
        fun awaitRunsMatching(members: List<String>) {
            val pending = (0 until partitions).toMutableSet()
            while (pending.isNotEmpty()) {
                val (member, partition) = checkNotNull(runs.poll(30, TimeUnit.SECONDS)) {
                    "partitions $pending did not run on their owner among $members"
                }
                if (PartitionAssignment.ownerOf(partition, members) == member) {
                    pending -= partition
                }
            }
        }

        try {
            nodes.forEach { it.start() }
            awaitRunsMatching(listOf("a", "b"))

            nodes[1].stop()
            runs.clear()

            awaitRunsMatching(listOf("a"))
            nodes[0].ownedPartitions.assert().isEqualTo((0 until partitions).toSet())
        } finally {
            nodes.forEach { it.close() }
        }
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
