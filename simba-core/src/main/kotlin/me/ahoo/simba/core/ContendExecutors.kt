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

import me.ahoo.simba.util.Threads
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Default executors for [LeaseContendService].
 *
 * Threads are daemon and reclaimed when idle, so an executor that is never shut down holds no threads
 * and does not keep the JVM alive.
 *
 * @author ahoo wang
 */
object ContendExecutors {
    private const val KEEP_ALIVE_SECONDS = 60L

    /**
     * Single-thread scheduler for contention triggers and lease watchdogs; it never runs blocking work.
     */
    @JvmStatic
    fun newScheduler(domain: String): ScheduledExecutorService {
        return ScheduledThreadPoolExecutor(1, Threads.defaultFactory(domain, daemon = true)).apply {
            setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            removeOnCancelPolicy = true
        }
    }

    /**
     * Executor for blocking backend calls. Unbounded, but each service keeps at most one call in flight,
     * so threads never exceed the number of contending services.
     */
    @JvmStatic
    fun newIoExecutor(domain: String): ExecutorService = newCachedExecutor(domain)

    /**
     * Executor for owner callbacks (`handleExecutor`). Each service dispatches its callbacks sequentially,
     * so threads never exceed the number of services notifying at the same time.
     */
    @JvmStatic
    fun newCallbackExecutor(domain: String): ExecutorService = newCachedExecutor(domain)

    private fun newCachedExecutor(domain: String): ExecutorService {
        return ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            Threads.defaultFactory(domain, daemon = true)
        )
    }
}
