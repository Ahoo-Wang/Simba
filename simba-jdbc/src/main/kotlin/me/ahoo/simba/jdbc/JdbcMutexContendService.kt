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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.ContendPeriod
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.util.Threads.defaultFactory
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
        private val log = KotlinLogging.logger {}
    }

    private var executorService: ScheduledThreadPoolExecutor? = null
    private val contendPeriod: ContendPeriod = ContendPeriod(contenderId)
    private val lifecycleGeneration = AtomicLong()

    @Volatile
    private var contendScheduledFuture: ScheduledFuture<*>? = null

    override fun startContend() {
        val generation = lifecycleGeneration.incrementAndGet()
        executorService =
            ScheduledThreadPoolExecutor(1, defaultFactory("JdbcSimba_${mutex}_$contenderId"))
        nextSchedule(initialDelay.toMillis(), generation)
    }

    private fun nextSchedule(nextDelay: Long, generation: Long) {
        log.debug {
            "nextSchedule - mutex:[$mutex] contenderId:[$contenderId] - nextDelay:[$nextDelay]."
        }
        if (!status.isActive || generation != lifecycleGeneration.get()) {
            /*
             * A contend task can still be in flight when stop() shuts the executor down
             * (JDBC calls are not interruptible); scheduling on from that path would throw
             * RejectedExecutionException that nobody observes.
             */
            log.warn {
                "nextSchedule - mutex:[$mutex] contenderId:[$contenderId] is not active[$status]."
            }
            return
        }
        contendScheduledFuture = executorService!!.schedule(
            { safeHandleContend(generation) },
            nextDelay,
            TimeUnit.MILLISECONDS
        )
    }

    override fun stopContend() {
        lifecycleGeneration.incrementAndGet()
        contendScheduledFuture?.cancel(true)
        executorService?.shutdown()
        notifyOwner(MutexOwner.NONE)
        mutexOwnerRepository.release(mutex, contenderId)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun safeHandleContend(generation: Long) {
        try {
            val mutexOwner = contend()
            if (!status.isActive || generation != lifecycleGeneration.get()) {
                if (mutexOwner.isOwner(contenderId)) {
                    mutexOwnerRepository.release(mutex, contenderId)
                }
                return
            }
            notifyOwner(mutexOwner)
            val nextDelay = contendPeriod.ensureNextDelay(mutexOwner)
            nextSchedule(nextDelay, generation)
        } catch (throwable: Throwable) {
            log.error(throwable) {
                "safeHandleContend - mutex:[$mutex] contenderId:[$contenderId] - failed:[${throwable.message}]."
            }
            if (status.isActive && generation == lifecycleGeneration.get() && isOwner) {
                notifyOwner(MutexOwner.NONE)
            }
            nextSchedule(ttl.toMillis(), generation)
        }
    }

    /**
     * 服务实例竞争领导权.
     */
    private fun contend(): MutexOwner {
        val mutexOwner =
            mutexOwnerRepository.acquireAndGetOwner(mutex, contenderId, ttl.toMillis(), transition.toMillis())
        log.debug {
            "contend - mutex:[$mutex] contenderId:[$contenderId] - succeeded:[${mutexOwner.isOwner(contenderId)}]."
        }
        return mutexOwner
    }
}
