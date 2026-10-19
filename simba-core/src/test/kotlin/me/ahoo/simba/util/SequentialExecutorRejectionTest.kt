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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class SequentialExecutorRejectionTest {
    @Test
    fun `a task queued while draining runs on the same drainer, even when the delegate now rejects`() {
        var accept = true
        val delegate = Executor { task -> if (accept) task.run() else throw RejectedExecutionException("closed") }
        val executor = SequentialExecutor(delegate)
        val ran = ArrayList<String>()
        executor.execute {
            ran += "first"
            accept = false
            executor.execute { ran += "second" }
        }
        ran.assert().isEqualTo(listOf("first", "second"))
        assertThrows<RejectedExecutionException> { executor.execute { ran += "third" } }
        ran.assert().isEqualTo(listOf("first", "second"))
    }
}
