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

package me.ahoo.simba.locker

import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.core.SameThreadExecutor
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Controllable contend service bound to the contender passed by the factory.
 * Tests flip ownership by calling [markOwner], which dispatches to
 * [AbstractMutexContendService.notifyOwner] -> contender.notifyOwner.
 */
private class ControllableContendService(
    contender: MutexContender
) : AbstractMutexContendService(contender, SameThreadExecutor) {
    override fun startContend() {}
    override fun stopContend() {}

    fun markOwner(ownerId: String) {
        notifyOwner(MutexOwner(ownerId, 0, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}

/**
 * Factory that builds a [ControllableContendService] for the given contender
 * (the locker) and exposes it so tests can drive ownership transitions.
 */
private class ControllableFactory : MutexContendServiceFactory {
    var service: ControllableContendService? = null
        private set

    override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
        val svc = ControllableContendService(mutexContender)
        service = svc
        return svc
    }
}

class SimbaLockerTest {
    @Test
    fun `acquire with timeout throws TimeoutException when ownership never acquired`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        val error = assertThrows<TimeoutException> {
            locker.acquire(Duration.ofMillis(50))
        }
        assertThat(error.message, containsString("Could not acquire"))
        assertThat(error.message, containsString("within timeout of 50ms"))
    }

    @Test
    fun `double acquire with timeout throws IllegalMonitorStateException`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        // The first acquire must genuinely succeed: acquire(timeout) only returns
        // normally once the contend service is the owner, and start() resets the
        // owner, so ownership is granted from a helper thread after this thread parks.
        acquireWithGrantedOwnership(locker, factory)

        val error = assertThrows<IllegalMonitorStateException> {
            locker.acquire(Duration.ofMillis(50))
        }
        assertThat(error.message, containsString("already owns this lock"))
    }

    @Test
    fun `acquire without timeout throws IllegalMonitorStateException on double acquire`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        // First acquire must succeed (ownership granted concurrently).
        acquireWithGrantedOwnership(locker, factory)

        val error = assertThrows<IllegalMonitorStateException> {
            locker.acquire()
        }
        assertThat(error.message, containsString("already owns this lock"))
    }

    @Test
    fun `acquire returns when onAcquired unparks the calling thread`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)
        val acquired = CountDownLatch(1)

        // Run acquire() on a separate thread; it parks inside LockSupport.park(this).
        // The main thread then marks ownership, which dispatches to the contender's
        // notifyOwner -> SimbaLocker.onAcquired -> LockSupport.unpark(owner thread).
        val ownerThread = Thread {
            locker.acquire()
            acquired.countDown()
        }
        ownerThread.isDaemon = true
        ownerThread.start()

        // Wait until the locker's contend service is created and acquire() has parked.
        // Poll the owner thread until it is WAITING (parked) before marking ownership,
        // so the unpark is guaranteed to find a parked thread.
        awaitThreadState(ownerThread, Thread.State.WAITING, 2, TimeUnit.SECONDS)
        factory.service!!.markOwner(locker.contenderId)

        // The CountDownLatch reaching zero proves acquire() returned after the unpark.
        assertThat("acquire should return after onAcquired unparks", acquired.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `onAcquired does not throw when no thread owns the locker`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        // Call onAcquired directly without a prior acquire() — OWNER[this] is null,
        // LockSupport.unpark(null) is a safe no-op.
        locker.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(locker.contenderId, 0, 100, 200)))
    }

    @Test
    fun `close throws IllegalStateException when contend service not running`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        val error = assertThrows<IllegalStateException> { locker.close() }
        assertThat(error.message, containsString("Cannot stop mutex:[m]"))
    }

    /**
     * Makes the locker's first acquire genuinely succeed.
     *
     * `acquire(timeout)` only returns normally once `contendService.isOwner` is true,
     * and `start()` resets the owner, so ownership cannot be pre-granted. A helper
     * thread waits until the acquirer has parked (TIMED_WAITING, i.e. past start()'s
     * resetOwner), then grants ownership, which dispatches onAcquired -> unpark ->
     * isOwner true -> acquire returns.
     */
    private fun acquireWithGrantedOwnership(locker: SimbaLocker, factory: ControllableFactory) {
        val ownerThread = Thread.currentThread()
        val granter = Thread {
            awaitThreadState(ownerThread, Thread.State.TIMED_WAITING, 2, TimeUnit.SECONDS)
            factory.service!!.markOwner(locker.contenderId)
        }
        granter.isDaemon = true
        granter.start()
        locker.acquire(Duration.ofSeconds(2))
    }

    private fun awaitThreadState(
        thread: Thread,
        state: Thread.State,
        timeout: Long,
        unit: TimeUnit
    ) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (thread.state == state) return
            Thread.sleep(10)
        }
        throw AssertionError("Thread did not reach state $state within $timeout $unit (was ${thread.state})")
    }
}
