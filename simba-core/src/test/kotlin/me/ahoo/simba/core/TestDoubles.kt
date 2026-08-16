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

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [MutexOwner] with a fixed [currentAt] for deterministic time-based tests.
 */
class FixedClockOwner(
    ownerId: String,
    ttlAt: Long,
    transitionAt: Long,
    private val fixedCurrentAt: Long
) : MutexOwner(ownerId = ownerId, acquiredAt = 0, ttlAt = ttlAt, transitionAt = transitionAt) {
    override val currentAt: Long
        get() = fixedCurrentAt
}

/**
 * [MutexContender] that records [onAcquired]/[onReleased] calls.
 * Inherits the default [notifyOwner] dispatch from [MutexContender].
 */
class FakeMutexContender(
    override val mutex: String,
    override val contenderId: String
) : MutexContender {
    val acquired = mutableListOf<MutexState>()
    val released = mutableListOf<MutexState>()

    var throwOnNotify: Throwable? = null

    override fun notifyOwner(mutexState: MutexState) {
        throwOnNotify?.let { throw it }
        super.notifyOwner(mutexState)
    }

    override fun onAcquired(mutexState: MutexState) {
        acquired += mutexState
    }

    override fun onReleased(mutexState: MutexState) {
        released += mutexState
    }
}

/**
 * Synchronous executor that runs tasks inline on the calling thread.
 */
object SameThreadExecutor : Executor {
    override fun execute(command: Runnable) {
        command.run()
    }
}

/**
 * Executor that pairs two submitted tasks at a barrier and then runs each on its
 * own thread, so both notifications enter [AbstractMutexRetrievalService.safeNotifyOwner]
 * at the same instant — a deterministic stand-in for a multi-threaded handleExecutor
 * (the production default is ForkJoinPool.commonPool).
 */
class BarrierPairExecutor : Executor {
    private val barrier = CyclicBarrier(2)

    override fun execute(command: Runnable) {
        thread(isDaemon = true) {
            runCatching { barrier.await(10, TimeUnit.SECONDS) }
            command.run()
        }
    }
}

/**
 * [MutexContender] that counts [onAcquired] calls atomically.
 * Unlike [FakeMutexContender] this is safe under concurrent notification.
 */
class ConcurrentCountingContender(
    override val mutex: String,
    override val contenderId: String
) : MutexContender {
    val acquiredCount = AtomicInteger()

    override fun onAcquired(mutexState: MutexState) {
        acquiredCount.incrementAndGet()
    }

    override fun onReleased(mutexState: MutexState) = Unit
}

/**
 * [AbstractMutexContendService] fake with controllable start/stop/notify behavior.
 * Uses [SameThreadExecutor] by default so [notifyOwner] runs inline.
 */
class FakeMutexContendService(
    contender: MutexContender,
    handleExecutor: Executor = SameThreadExecutor,
    var throwOnStartContend: Throwable? = null,
    var throwOnStopContend: Throwable? = null
) : AbstractMutexContendService(contender, handleExecutor) {
    var startContendCalled = false
        private set
    var stopContendCalled = false
        private set

    override fun startContend() {
        startContendCalled = true
        throwOnStartContend?.let { throw it }
    }

    override fun stopContend() {
        stopContendCalled = true
        throwOnStopContend?.let { throw it }
    }

    /** Exposes the protected [notifyOwner] for tests. */
    fun publishOwner(newOwner: MutexOwner): CompletableFuture<Void> {
        return notifyOwner(newOwner)
    }
}

/*
 * No shared MutexContendServiceFactory is provided here because the classes that need one
 * (SimbaLocker, AbstractScheduler) build their contend service *inside their own constructor*
 * via the factory — so the factory must construct the service lazily, using the contender
 * passed to createMutexContendService (which is the locker/scheduler itself). A pre-built
 * factory cannot work because its service's contender would be fixed before the locker exists.
 * See SimbaLockerTest.ControllableFactory and AbstractSchedulerTest.CapturingFactory.
 */
