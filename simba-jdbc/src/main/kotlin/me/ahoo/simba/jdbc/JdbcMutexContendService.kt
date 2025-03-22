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

import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.ContendPeriod
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.util.Threads.defaultFactory
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Jdbc Mutex Contend Service.
 *
 * @author ahoo wang
 */
class JdbcMutexContendService(
    mutexContender: MutexContender,
    handleExecutor: Executor,
    private val mutexOwnerRepository: MutexOwnerRepository,
    private val initialDelay: Duration,
    private val ttl: Duration,
    private val transition: Duration
) : AbstractMutexContendService(mutexContender, handleExecutor) {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcMutexContendService::class.java)
    }

    private var executorService: ScheduledThreadPoolExecutor? = null
    private val contendPeriod: ContendPeriod = ContendPeriod(contenderId)

    @Volatile
    private var contendScheduledFuture: ScheduledFuture<*>? = null

    override fun startContend() {
        executorService =
            ScheduledThreadPoolExecutor(1, defaultFactory("JdbcSimba_${mutex}_$contenderId"))
        nextSchedule(initialDelay.toMillis())
    }

    private fun nextSchedule(nextDelay: Long) {
        if (log.isDebugEnabled) {
            log.debug(
                "nextSchedule - mutex:[{}] contenderId:[{}] - nextDelay:[{}].",
                mutex,
                contenderId,
                nextDelay
            )
        }
        contendScheduledFuture = executorService!!.schedule({ safeHandleContend() }, nextDelay, TimeUnit.MILLISECONDS)
    }

    override fun stopContend() {
        contendScheduledFuture?.cancel(true)
        executorService?.shutdown()
        notifyOwner(MutexOwner.NONE)
        mutexOwnerRepository.release(mutex, contenderId)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun safeHandleContend() {
        try {
            val mutexOwner = contend()
            notifyOwner(mutexOwner)
            val nextDelay = contendPeriod.ensureNextDelay(mutexOwner)
            nextSchedule(nextDelay)
        } catch (throwable: Throwable) {
            if (log.isErrorEnabled) {
                log.error(throwable.message, throwable)
            }
            nextSchedule(ttl.toMillis())
        }
    }

    /**
     * 服务实例竞争领导权.
     */
    private fun contend(): MutexOwner {
        val mutexOwner =
            mutexOwnerRepository.acquireAndGetOwner(mutex, contenderId, ttl.toMillis(), transition.toMillis())
        if (log.isDebugEnabled) {
            log.debug(
                "contend - mutex:[{}] contenderId:[{}] - succeeded:[{}].",
                mutex,
                contenderId,
                mutexOwner.isOwner(contenderId)
            )
        }
        return mutexOwner
    }
}
