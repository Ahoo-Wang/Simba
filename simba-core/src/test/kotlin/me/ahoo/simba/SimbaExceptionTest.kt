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

package me.ahoo.simba

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test

class SimbaExceptionTest {
    @Test
    fun `is a RuntimeException`() {
        assertThat(SimbaException(), instanceOf(RuntimeException::class.java))
    }

    @Test
    fun `default constructor has null message and cause`() {
        val ex = SimbaException()
        assertThat(ex.message, nullValue())
        assertThat(ex.cause, nullValue())
    }

    @Test
    fun `message constructor sets message`() {
        val ex = SimbaException("msg")
        assertThat(ex.message, equalTo("msg"))
        assertThat(ex.cause, nullValue())
    }

    @Test
    fun `message and cause constructor sets both`() {
        val cause = IllegalStateException("root")
        val ex = SimbaException("msg", cause)
        assertThat(ex.message, equalTo("msg"))
        assertThat(ex.cause, sameInstance(cause))
    }

    @Test
    fun `cause constructor sets cause`() {
        val cause = IllegalStateException("root")
        val ex = SimbaException(cause)
        assertThat(ex.cause, sameInstance(cause))
    }

    @Test
    fun `full constructor sets suppression and stack trace flags`() {
        val cause = IllegalStateException("root")
        val ex = SimbaException("msg", cause, true, false)

        assertThat(ex.message, equalTo("msg"))
        assertThat(ex.cause, sameInstance(cause))
    }

    @Test
    fun `open class allows subclassing`() {
        class CustomSimbaException : SimbaException("custom")

        val sub = CustomSimbaException()
        assertThat(sub, instanceOf(SimbaException::class.java))
        assertThat(sub.message, equalTo("custom"))
    }
}
