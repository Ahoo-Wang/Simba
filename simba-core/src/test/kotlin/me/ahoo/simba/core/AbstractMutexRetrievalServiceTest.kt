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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlin.concurrent.thread

class AbstractMutexRetrievalServiceTest {
    private fun newService(): FakeMutexContendService {
        val contender = FakeMutexContender("m", "c1")
        return FakeMutexContendService(contender)
    }

    @Test
    fun `start transitions INITIAL to RUNNING and calls startContend`() {
        val service = newService()

        service.start()

        assertThat(service.status, equalTo(MutexRetrievalService.Status.RUNNING))
        assertThat(service.startContendCalled, equalTo(true))
        service.stop()
    }

    @Test
    fun `start from RUNNING throws IllegalStateException`() {
        val service = newService()
        service.start()

        val error = assertThrows<IllegalStateException> { service.start() }
        assertThat(error.message, equalTo("Cannot start from state [RUNNING]. Expected: [INITIAL]"))

        service.stop()
    }

    @Test
    fun `start rollback to INITIAL when startContend throws`() {
        val service = newService()
        service.throwOnStartContend = IllegalStateException("boom")

        val error = assertThrows<IllegalStateException> { service.start() }

        assertThat(error.message, equalTo("boom"))
        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))
    }

    @Test
    fun `stop transitions RUNNING to INITIAL and calls stopContend`() {
        val service = newService()
        service.start()

        service.stop()

        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))
        assertThat(service.stopContendCalled, equalTo(true))
    }

    @Test
    fun `stop from INITIAL throws IllegalStateException`() {
        val service = newService()

        val error = assertThrows<IllegalStateException> { service.stop() }
        assertThat(error.message, equalTo("Cannot stop mutex:[m] from state:[INITIAL]. Expected:[RUNNING]"))
    }

    @Test
    fun `stop resets to INITIAL even when stopContend throws`() {
        val service = newService()
        service.start()
        service.throwOnStopContend = IllegalStateException("stop-boom")

        val error = assertThrows<IllegalStateException> { service.stop() }

        assertThat(error.message, equalTo("stop-boom"))
        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))
    }

    @Test
    fun `publishOwner updates mutexState and notifies retriever`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()
        val previous = MutexOwner("other", 0, 50, 100)
        service.publishOwner(previous)

        val newOwner = MutexOwner("c1", 0, 100, 200)
        service.publishOwner(newOwner).join()

        assertThat(service.afterOwner, sameInstance(newOwner))
        assertThat(service.beforeOwner, sameInstance(previous))
        // contender became owner -> onAcquired fired
        assertThat(contender.acquired.size, equalTo(1))
        service.stop()
    }

    @Test
    fun `publishOwner swallows retriever exception and keeps mutexState`() {
        val contender = FakeMutexContender("m", "c1")
        contender.throwOnNotify = IllegalStateException("notify-boom")
        val service = FakeMutexContendService(contender)
        service.start()

        val newOwner = MutexOwner("c1", 0, 100, 200)
        // Should not throw despite retriever.notifyOwner throwing
        service.publishOwner(newOwner).join()

        // mutexState was assigned BEFORE the throw (order-of-assignment contract)
        assertThat(service.afterOwner, sameInstance(newOwner))
        // no exception escaped the CompletableFuture
        assertThat(contender.acquired.size, equalTo(0))
        service.stop()
    }

    @Test
    fun `close delegates to stop and throws when not running`() {
        val service = newService()

        val error = assertThrows<IllegalStateException> { service.close() }
        assertThat(error.message, equalTo("Cannot stop mutex:[m] from state:[INITIAL]. Expected:[RUNNING]"))
    }

    @Test
    fun `start resets owner to NONE via resetOwner`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()
        service.publishOwner(MutexOwner("c1", 0, 100, 200)).join()
        assertThat(service.hasOwner(), equalTo(true))

        service.stop()
        service.start()

        // startRetrieval -> resetOwner -> mutexState = NONE
        assertThat(service.mutexState, equalTo(MutexState.NONE))
        service.stop()
    }

    @Test
    fun `concurrent duplicate owner notifications dispatch onAcquired at most once`() {
        // The read->write window inside safeNotifyOwner is nanoseconds wide, so a single
        // barrier-paired pair rarely interleaves; thousands of attempts make hitting the
        // window a statistical certainty before the fix, while the fix passes deterministically.
        for (attempt in 0 until 5000) {
            val contender = ConcurrentCountingContender("m", "c1-$attempt")
            val service = FakeMutexContendService(contender, BarrierPairExecutor())
            val selfOwner = MutexOwner(contender.contenderId, 0, Long.MAX_VALUE, Long.MAX_VALUE)
            // notifications are only meaningful while the service is active
            service.start()

            val first = service.publishOwner(selfOwner)
            val second = service.publishOwner(selfOwner)
            first.join()
            second.join()

            assertThat(
                "attempt [$attempt]: duplicate onAcquired dispatched for a single NONE->self transition",
                contender.acquiredCount.get(),
                equalTo(1)
            )
            service.stop()
        }
    }

    @Test
    fun `self notification submitted while active but executing after stop must not revive ownership`() {
        val contender = FakeMutexContender("m", "c1")
        val executor = GatedExecutor()
        val service = FakeMutexContendService(contender, executor)
        service.start()

        // submitted while RUNNING, but the dispatch happens only after stop() completed:
        // a multi-threaded handleExecutor may execute it after the stop notification
        val future = service.publishOwner(MutexOwner("c1", 0, 100, 200))
        service.stop()
        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))

        executor.release()
        future.join()

        assertThat(service.mutexState, equalTo(MutexState.NONE))
        assertThat(contender.acquired.size, equalTo(0))
    }

    @Test
    fun `stop release notification executing after stop must still be applied`() {
        val contender = FakeMutexContender("m", "c1")
        val executor = GatedExecutor()
        val service = FakeMutexContendService(contender, executor)
        service.start()
        service.publishOwner(MutexOwner("c1", 0, 100, 200)).join()
        assertThat(service.hasOwner(), equalTo(true))

        // the release notification is submitted while stopping and may execute once the
        // service is fully stopped (INITIAL) — it must still be applied
        val future = service.publishOwner(MutexOwner.NONE)
        service.stop()

        executor.release()
        future.join()

        assertThat(service.mutexState.after, equalTo(MutexOwner.NONE))
        assertThat(contender.released.size, equalTo(1))
    }

    private class GatedExecutor : Executor {
        private val gate = CountDownLatch(1)

        override fun execute(command: Runnable) {
            thread(isDaemon = true) {
                gate.await()
                command.run()
            }
        }

        fun release() {
            gate.countDown()
        }
    }
}
