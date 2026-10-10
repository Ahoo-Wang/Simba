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
package me.ahoo.simba.core

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.Delayed
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Deterministic tests of the contention loop: scheduled triggers and backend calls are captured and run
 * explicitly, so "in flight" means a captured backend call that has not run yet.
 */
class LeaseContendServiceTest {
    private val config = LeaseConfig(Duration.ofMillis(500), Duration.ofMillis(300))
    private val contender = FakeMutexContender("m", "c1")
    private val store = FakeLeaseStore()
    private val scheduler = ManualScheduler()
    private val io = ManualExecutor()
    private val service = TestLeaseContendService(contender, store, config, scheduler, io)

    @Test
    fun `observer receives contention, ownership and lease expiry events`() {
        val observer = RecordingObserver()
        val observed = TestLeaseContendService(contender, store, config, scheduler, io, observer = observer)
        observed.start()
        scheduler.runNext()
        io.runNext()
        scheduler.runNext()
        io.runNext()
        store.failNext = IllegalStateException("backend unavailable")
        scheduler.runNext()
        io.runNext()
        scheduler.watchdogs.last().run()
        store.otherOwner = "c2"
        scheduler.runNext()
        io.runNext()

        observer.events.toList().assert().containsExactly(
            "contend:m:acquire:OWNER",
            "acquired:m",
            "contend:m:renew:OWNER",
            "contend:m:renew:FAILED",
            "expired:m",
            "released:m",
            "contend:m:acquire:OTHER"
        )
    }

    @Test
    fun `a failing observer does not affect contention`() {
        val observed = TestLeaseContendService(
            contender,
            store,
            config,
            scheduler,
            io,
            observer = RecordingObserver(failing = true)
        )
        observed.start()
        scheduler.runNext()
        io.runNext()

        observed.isOwner.assert().isTrue()
        contender.acquired.size.assert().isEqualTo(1)
        scheduler.pending.size.assert().isEqualTo(1)
    }

    @Test
    fun `acquires and schedules renewal at ttl`() {
        service.start()
        scheduler.runNext()
        io.runNext()

        service.isOwner.assert().isTrue()
        contender.acquired.size.assert().isEqualTo(1)
        scheduler.pending.single().delayMillis.assert().isBetween(495, 500)
        store.renewFlags.assert().containsExactly(false)
    }

    @Test
    fun `owner renews`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        scheduler.runNext()
        io.runNext()

        store.renewFlags.assert().containsExactly(false, true)
        contender.acquired.size.assert().isEqualTo(1)
    }

    @Test
    fun `contender retries around transition end`() {
        store.otherOwner = "c2"
        service.start()
        scheduler.runNext()
        io.runNext()

        service.isOwner.assert().isFalse()
        scheduler.pending.single().delayMillis.assert().isBetween(800 - 205, 800 + 1000)
    }

    @Test
    fun `acquisition arms a watchdog at lease end`() {
        service.start()
        scheduler.runNext()
        io.runNext()

        scheduler.watchdogs.single().delayMillis.assert().isBetween(785, 800)
    }

    @Test
    fun `watchdog revokes ownership at lease end`() {
        service.start()
        scheduler.runNext()
        io.runNext()

        scheduler.watchdogs.single().run()

        service.isOwner.assert().isFalse()
        contender.released.size.assert().isEqualTo(1)
    }

    @Test
    fun `watchdog revokes ownership while renewal hangs and renewal success restores it`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        scheduler.runNext()
        val hungRenewal = io.take()

        scheduler.watchdogs.single().run()
        service.isOwner.assert().isFalse()

        hungRenewal.run()
        service.isOwner.assert().isTrue()
        contender.acquired.size.assert().isEqualTo(2)
        scheduler.watchdogs.size.assert().isEqualTo(1)
    }

    @Test
    fun `renewal re-arms the watchdog`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        val first = scheduler.watchdogs.single()
        scheduler.runNext()
        io.runNext()

        first.isCancelled.assert().isTrue()
        (scheduler.watchdogs.single() === first).assert().isFalse()
    }

    @Test
    fun `failure within the lease keeps ownership and retries with backoff`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        store.failNext = IllegalStateException("backend unavailable")
        scheduler.runNext()
        io.runNext()

        service.isOwner.assert().isTrue()
        contender.released.assert().isEmpty()
        scheduler.pending.single().delayMillis.assert().isBetween(385, 400)
        scheduler.watchdogs.size.assert().isEqualTo(1)
    }

    @Test
    fun `failure after the lease ended revokes and retries after ttl`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        scheduler.watchdogs.single().run()
        store.failNext = IllegalStateException("backend unavailable")
        scheduler.runNext()
        io.runNext()

        service.isOwner.assert().isFalse()
        contender.released.size.assert().isEqualTo(1)
        scheduler.pending.single().delayMillis.assert().isEqualTo(500)
    }

    @Test
    fun `failure while not owner retries after ttl`() {
        store.failNext = IllegalStateException("backend unavailable")
        service.start()
        scheduler.runNext()
        io.runNext()

        service.isOwner.assert().isFalse()
        scheduler.pending.single().delayMillis.assert().isEqualTo(500)
    }

    @Test
    fun `losing the lease to another owner disarms the watchdog`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        store.otherOwner = "c2"
        scheduler.runNext()
        io.runNext()

        scheduler.watchdogs.assert().isEmpty()
        service.isOwner.assert().isFalse()
    }

    @Test
    fun `unbounded lease arms no watchdog`() {
        store.unbounded = true
        service.start()
        scheduler.runNext()
        io.runNext()

        service.isOwner.assert().isTrue()
        scheduler.watchdogs.assert().isEmpty()
    }

    @Test
    fun `stop disarms the watchdog`() {
        service.start()
        scheduler.runNext()
        io.runNext()

        service.stop()

        scheduler.watchdogs.assert().isEmpty()
    }

    @Test
    fun `stop cancels schedule, unsubscribes and releases`() {
        service.start()
        scheduler.runNext()
        io.runNext()

        service.stop()

        scheduler.pending.assert().isEmpty()
        service.stopCalls.assert().isEqualTo(1)
        store.ownerId.assert().isEmpty()
        contender.released.size.assert().isEqualTo(1)
    }

    @Test
    fun `stop releases even when onStop fails`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        service.failOnStop = true

        assertThrows<IllegalStateException> { service.stop() }

        store.ownerId.assert().isEmpty()
        service.status.assert().isEqualTo(MutexRetrievalService.Status.INITIAL)
        contender.released.size.assert().isEqualTo(1)
    }

    @Test
    fun `acquisition completing after stop is released without rescheduling`() {
        service.start()
        scheduler.runNext()
        service.stop()

        io.runNext()

        store.contendCalls.assert().isEqualTo(1)
        store.releaseCalls.assert().isEqualTo(2)
        store.ownerId.assert().isEmpty()
        scheduler.pending.assert().isEmpty()
        contender.acquired.assert().isEmpty()
    }

    @Test
    fun `stale acquisition does not release restarted lifecycle lease`() {
        service.start()
        scheduler.runNext()
        service.stop()
        service.start()
        scheduler.runNext()
        val staleCall = io.take()
        io.runNext()

        staleCall.run()

        store.releaseCalls.assert().isEqualTo(1)
        store.ownerId.assert().isEqualTo("c1")
        service.isOwner.assert().isTrue()
        scheduler.pending.size.assert().isEqualTo(1)
    }

    @Test
    fun `stale failure does not revoke restarted lifecycle ownership`() {
        service.start()
        scheduler.runNext()
        service.stop()
        service.start()
        scheduler.runNext()
        val staleCall = io.take()
        io.runNext()
        contender.released.clear()

        store.failNext = IllegalStateException("stale failure")
        staleCall.run()

        service.isOwner.assert().isTrue()
        contender.released.assert().isEmpty()
        scheduler.pending.size.assert().isEqualTo(1)
    }

    @Test
    fun `failed restart rolls back so a stale acquisition is compensated`() {
        service.start()
        scheduler.runNext()
        service.stop()
        scheduler.reject = true

        assertThrows<RejectedExecutionException> { service.start() }
        io.runNext()

        service.status.assert().isEqualTo(MutexRetrievalService.Status.INITIAL)
        service.stopCalls.assert().isEqualTo(2)
        store.ownerId.assert().isEmpty()
    }

    @Test
    fun `contendNow replaces a pending schedule`() {
        store.otherOwner = "c2"
        service.start()
        scheduler.runNext()
        io.runNext()

        service.requestContend()

        scheduler.pending.single().delayMillis.assert().isEqualTo(0)
    }

    @Test
    fun `contendNow during an in-flight call reschedules immediately after it`() {
        store.otherOwner = "c2"
        service.start()
        scheduler.runNext()
        service.requestContend()
        scheduler.pending.assert().isEmpty()

        io.runNext()

        scheduler.pending.single().delayMillis.assert().isEqualTo(0)
    }

    @Test
    fun `superseded trigger does not contend`() {
        store.otherOwner = "c2"
        service.start()
        val superseded = scheduler.pending.single()
        service.requestContend()

        superseded.run()

        io.pendingCount.assert().isEqualTo(0)
    }

    @Test
    fun `rejected backend call retries after ttl`() {
        service.start()
        io.reject = true
        scheduler.runNext()

        store.contendCalls.assert().isEqualTo(0)
        scheduler.pending.single().delayMillis.assert().isEqualTo(500)
    }

    @Test
    fun `renew decision follows the store reply, not the asynchronous owner view`() {
        val deferredNotifications = ManualExecutor()
        val deferred = TestLeaseContendService(contender, store, config, scheduler, io, deferredNotifications)
        deferred.start()
        scheduler.runNext()
        io.runNext()
        deferred.isOwner.assert().isFalse()

        scheduler.runNext()
        io.runNext()

        store.renewFlags.assert().containsExactly(false, true)
    }

    @Test
    fun `losing the lease switches back to acquire`() {
        service.start()
        scheduler.runNext()
        io.runNext()
        store.otherOwner = "c2"
        scheduler.runNext()
        io.runNext()
        scheduler.runNext()
        io.runNext()

        store.renewFlags.assert().containsExactly(false, true, false)
    }

    @Test
    fun `rejected reschedule after the scheduler shut down is logged, not thrown`() {
        service.start()
        scheduler.runNext()
        scheduler.reject = true

        io.runNext()

        service.isOwner.assert().isTrue()
        scheduler.pending.assert().isEmpty()
    }

    private class TestLeaseContendService(
        contender: MutexContender,
        store: MutexLeaseStore,
        config: LeaseConfig,
        scheduler: ManualScheduler,
        io: Executor,
        handleExecutor: Executor = Executor { it.run() },
        observer: ContendObserver = ContendObserver.NOOP
    ) : LeaseContendService(contender, handleExecutor, store, config, scheduler, io, observer) {
        var stopCalls = 0
        var failOnStop = false

        override fun onStop() {
            stopCalls++
            check(!failOnStop) { "onStop failed" }
        }

        fun requestContend() = contendNow()
    }

    private class FakeLeaseStore : MutexLeaseStore {
        var ownerId = ""
        var otherOwner: String? = null
        var failNext: Throwable? = null
        var unbounded = false
        val renewFlags = mutableListOf<Boolean>()
        var contendCalls = 0
        var releaseCalls = 0

        override fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner {
            contendCalls++
            renewFlags += renew
            failNext?.let {
                failNext = null
                throw it
            }
            val holder = otherOwner ?: contenderId.also { ownerId = it }
            if (unbounded) {
                return observedOwner(holder, Long.MAX_VALUE, Long.MAX_VALUE, NOW)
            }
            return observedOwner(holder, NOW + config.ttlMillis, NOW + config.leaseMillis, NOW)
        }

        override fun release(mutex: String, contenderId: String): Boolean {
            releaseCalls++
            if (ownerId != contenderId) {
                return false
            }
            ownerId = ""
            return true
        }

        companion object {
            const val NOW = 1_000L
        }
    }

    /**
     * Contention triggers are scheduled in milliseconds and lease watchdogs in nanoseconds;
     * the time unit tells them apart.
     */
    private class ManualScheduler : ScheduledThreadPoolExecutor(1) {
        private val tasks = mutableListOf<ManualTask>()
        var reject = false

        val pending: List<ManualTask>
            get() = tasks.filter { !it.isDone && !it.watchdog }

        val watchdogs: List<ManualTask>
            get() = tasks.filter { !it.isDone && it.watchdog }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (reject) {
                throw RejectedExecutionException("rejected")
            }
            return ManualTask(command, unit.toMillis(delay), unit == TimeUnit.NANOSECONDS).also { tasks += it }
        }

        fun runNext() {
            pending.first().run()
        }
    }

    private class ManualTask(command: Runnable, val delayMillis: Long, val watchdog: Boolean) :
        FutureTask<Unit>(command, Unit),
        ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(delayMillis, TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed): Int = 0
    }

    private class ManualExecutor : Executor {
        private val calls = ArrayDeque<Runnable>()
        var reject = false

        val pendingCount: Int
            get() = calls.size

        override fun execute(command: Runnable) {
            if (reject) {
                throw RejectedExecutionException("rejected")
            }
            calls.add(command)
        }

        fun take(): Runnable = calls.removeFirst()

        fun runNext() {
            take().run()
        }
    }
}
