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

package me.ahoo.simba.engine

import me.ahoo.simba.util.Threads
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One virtual thread per task when the runtime has them (Java 21+), otherwise a cached daemon pool whose idle
 * threads are reclaimed. The bytecode stays on the Java 17 baseline, so virtual threads are looked up reflectively.
 */
@InternalSimbaApi
object VirtualThreads {
    private val factory: (() -> ExecutorService)? = try {
        val method = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
        val create: () -> ExecutorService = { method.invoke(null) as ExecutorService }
        create
    } catch (_: NoSuchMethodException) {
        null
    }

    val available: Boolean get() = factory != null

    fun newExecutor(domain: String): ExecutorService =
        factory?.invoke() ?: ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            Threads.defaultFactory(domain, daemon = true),
        )

    private const val IDLE_SECONDS = 30L
}
