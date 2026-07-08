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
import org.hamcrest.Matchers.blankOrNullString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbstractMutexContenderTest {
    private fun newContender(mutex: String, contenderId: String = "c1"): AbstractMutexContender {
        return object : AbstractMutexContender(mutex, contenderId) {}
    }

    @Test
    fun `rejects blank mutex`() {
        val error = assertThrows<IllegalArgumentException> { newContender("") }
        assertThat(error.message, equalTo("mutex must not be blank!"))
    }

    @Test
    fun `rejects whitespace-only mutex`() {
        val error = assertThrows<IllegalArgumentException> { newContender("   ") }
        assertThat(error.message, equalTo("mutex must not be blank!"))
    }

    @Test
    fun `rejects blank contenderId`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m", "") }
        assertThat(error.message, equalTo("contenderId must not be blank!"))
    }

    @Test
    fun `rejects whitespace-only contenderId`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m", "  ") }
        assertThat(error.message, equalTo("contenderId must not be blank!"))
    }

    @Test
    fun `default contenderId is generated host format`() {
        val contender = object : AbstractMutexContender("m") {}
        assertThat(contender.contenderId, not(blankOrNullString()))
        assertThat(
            contender.contenderId,
            matchesPattern("\\d+:\\d+@.+")
        )
    }

    @Test
    fun `onAcquired and onReleased do not throw`() {
        val contender = newContender("m", "c1")
        val state = MutexState(MutexOwner.NONE, MutexOwner("c1", 0, 100, 200))

        contender.onAcquired(state)
        contender.onReleased(state)
    }
}
