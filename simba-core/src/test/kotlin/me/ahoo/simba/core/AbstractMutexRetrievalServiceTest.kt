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
}
