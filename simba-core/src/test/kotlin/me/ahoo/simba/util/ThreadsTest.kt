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

package me.ahoo.simba.util

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

class ThreadsTest {
    @Test
    fun `defaultFactory creates non-daemon threads`() {
        val factory = Threads.defaultFactory("domain")

        val thread = factory.newThread { }

        assertThat(thread.isDaemon, `is`(false))
    }

    @Test
    fun `defaultFactory names threads with domain counter`() {
        val factory = Threads.defaultFactory("worker")

        val first = factory.newThread { }
        val second = factory.newThread { }

        assertThat(first.name, equalTo("worker-0"))
        assertThat(second.name, equalTo("worker-1"))
    }
}
