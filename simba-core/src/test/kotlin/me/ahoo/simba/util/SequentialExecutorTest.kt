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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class SequentialExecutorTest {
    @Test
    fun `runs tasks in submission order on a thread pool`() {
        val pool = Executors.newFixedThreadPool(4)
        val executor = SequentialExecutor(pool)
        val order = mutableListOf<Int>()
        val done = CountDownLatch(1000)
        repeat(1000) { index ->
            executor.execute {
                order += index
                done.countDown()
            }
        }

        done.await(5, TimeUnit.SECONDS).assert().isTrue()
        order.assert().isEqualTo((0 until 1000).toList())
        pool.shutdown()
    }

    @Test
    fun `nested submissions on a direct delegate run after the current task without recursion`() {
        val executor = SequentialExecutor(Executor { it.run() })
        val order = mutableListOf<String>()

        executor.execute {
            order += "outer-start"
            executor.execute { order += "inner" }
            order += "outer-end"
        }

        order.assert().containsExactly("outer-start", "outer-end", "inner")
    }

    @Test
    fun `a failing task does not stop later tasks`() {
        val executor = SequentialExecutor(Executor { it.run() })
        val ran = mutableListOf<Int>()

        executor.execute { error("boom") }
        executor.execute { ran += 2 }

        ran.assert().containsExactly(2)
    }

    @Test
    fun `rejection discards the task and keeps the executor usable`() {
        var reject = true
        val ran = mutableListOf<Int>()
        val executor = SequentialExecutor { command ->
            if (reject) throw RejectedExecutionException("rejected")
            command.run()
        }

        assertThrows<RejectedExecutionException> { executor.execute { ran += 1 } }
        reject = false
        executor.execute { ran += 2 }

        ran.assert().containsExactly(2)
    }
}
