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
import org.junit.jupiter.api.Test

class MutexRetrievalServiceTest {
    @Test
    fun `Status INITIAL is not active`() {
        assertThat(MutexRetrievalService.Status.INITIAL.isActive, equalTo(false))
    }

    @Test
    fun `Status STARTING is active`() {
        assertThat(MutexRetrievalService.Status.STARTING.isActive, equalTo(true))
    }

    @Test
    fun `Status RUNNING is active`() {
        assertThat(MutexRetrievalService.Status.RUNNING.isActive, equalTo(true))
    }

    @Test
    fun `Status STOPPING is not active`() {
        assertThat(MutexRetrievalService.Status.STOPPING.isActive, equalTo(false))
    }

    @Test
    fun `hasOwner is false when afterOwner is NONE by identity`() {
        val service = FakeMutexContendService(FakeMutexContender("m", "c1"))
        // mutexState defaults to MutexState.NONE -> afterOwner is MutexOwner.NONE
        assertThat(service.hasOwner(), equalTo(false))
    }

    @Test
    fun `hasOwner is true when afterOwner is a real owner`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()

        service.publishOwner(MutexOwner("c1", 0, 100, 200)).join()

        assertThat(service.hasOwner(), equalTo(true))
        service.stop()
    }
}
