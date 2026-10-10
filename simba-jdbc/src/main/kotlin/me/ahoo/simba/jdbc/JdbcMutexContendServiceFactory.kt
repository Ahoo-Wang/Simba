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
package me.ahoo.simba.jdbc

import me.ahoo.simba.core.ContendExecutors
import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService

/**
 * Jdbc Mutex Contend Service Factory.
 *
 * All created services share [scheduledExecutorService] (contention triggers) and [ioExecutor]
 * (database calls). The factory owns both: [close] shuts them down, so pass dedicated executors.
 *
 * @author ahoo wang
 */
class JdbcMutexContendServiceFactory @JvmOverloads constructor(
    private val mutexOwnerRepository: MutexOwnerRepository,
    private val handleExecutor: Executor = ForkJoinPool.commonPool(),
    initialDelay: Duration,
    ttl: Duration,
    transition: Duration,
    private val scheduledExecutorService: ScheduledExecutorService = ContendExecutors.newScheduler("simba-jdbc"),
    private val ioExecutor: ExecutorService = ContendExecutors.newIoExecutor("simba-jdbc-io"),
    private val observer: ContendObserver = ContendObserver.NOOP
) : MutexContendServiceFactory, AutoCloseable {
    private val leaseConfig = LeaseConfig(ttl, transition, initialDelay)

    override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
        return JdbcMutexContendService(
            mutexContender = mutexContender,
            handleExecutor = handleExecutor,
            mutexOwnerRepository = mutexOwnerRepository,
            initialDelay = leaseConfig.initialDelay,
            ttl = leaseConfig.ttl,
            transition = leaseConfig.transition,
            scheduler = scheduledExecutorService,
            ioExecutor = ioExecutor,
            observer = observer
        )
    }

    override fun close() {
        scheduledExecutorService.shutdown()
        ioExecutor.shutdown()
    }
}
