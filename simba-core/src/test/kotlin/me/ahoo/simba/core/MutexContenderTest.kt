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

class MutexContenderTest {
    private val ownerA = MutexOwner("A", 0, 100, 200)
    private val ownerB = MutexOwner("B", 0, 100, 200)

    @Test
    fun `notifyOwner skips callbacks when state unchanged`() {
        val contender = FakeMutexContender("m", "A")

        contender.notifyOwner(MutexState(ownerA, ownerA))

        assertThat(contender.acquired.size, equalTo(0))
        assertThat(contender.released.size, equalTo(0))
    }

    @Test
    fun `notifyOwner fires onAcquired when contender becomes owner`() {
        val contender = FakeMutexContender("m", "B")

        contender.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(contender.acquired.size, equalTo(1))
        assertThat(contender.released.size, equalTo(0))
    }

    @Test
    fun `notifyOwner fires onReleased when contender loses ownership`() {
        val contender = FakeMutexContender("m", "A")

        contender.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(contender.acquired.size, equalTo(0))
        assertThat(contender.released.size, equalTo(1))
    }

    @Test
    fun `notifyOwner fires only onAcquired for the new owner on role swap`() {
        val newOwner = FakeMutexContender("m", "B")

        newOwner.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(newOwner.acquired.size, equalTo(1))
        assertThat(newOwner.released.size, equalTo(0))
    }

    @Test
    fun `notifyOwner fires only onReleased for the previous owner on role swap`() {
        val previousOwner = FakeMutexContender("m", "A")

        previousOwner.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(previousOwner.acquired.size, equalTo(0))
        assertThat(previousOwner.released.size, equalTo(1))
    }
}
