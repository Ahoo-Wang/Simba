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
    fun `rejects mutex longer than 66 characters`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m".repeat(67)) }
        assertThat(error.message, equalTo("mutex must not exceed 66 characters!"))
    }

    @Test
    fun `accepts mutex of exactly 66 characters`() {
        newContender("m".repeat(66))
    }

    @Test
    fun `rejects contenderId longer than 128 characters`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m", "c".repeat(129)) }
        assertThat(error.message, equalTo("contenderId must not exceed 128 characters!"))
    }

    @Test
    fun `accepts contenderId of exactly 128 characters`() {
        newContender("m", "c".repeat(128))
    }

    @Test
    fun `rejects contenderId containing the owner event delimiter`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m", "c1@@c2") }
        assertThat(error.message, equalTo("contenderId must not contain [@@]!"))
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
