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
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.LeaseContendService
import me.ahoo.simba.core.MutexContender
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/**
 * Jdbc Mutex Contend Service.
 *
 * Without an explicit [scheduler], the service uses its own single daemon thread that is reclaimed when idle.
 *
 * @author ahoo wang
 */
class JdbcMutexContendService @JvmOverloads constructor(
    mutexContender: MutexContender,
    handleExecutor: Executor,
    mutexOwnerRepository: MutexOwnerRepository,
    initialDelay: Duration,
    ttl: Duration,
    transition: Duration,
    scheduler: ScheduledExecutorService =
        ContendExecutors.newScheduler("JdbcSimba_${mutexContender.mutex}_${mutexContender.contenderId}"),
    ioExecutor: Executor = Executor { it.run() }
) : LeaseContendService(
    contender = mutexContender,
    handleExecutor = handleExecutor,
    leaseStore = JdbcMutexLeaseStore(mutexOwnerRepository),
    leaseConfig = LeaseConfig(ttl, transition, initialDelay),
    scheduler = scheduler,
    ioExecutor = ioExecutor
)
