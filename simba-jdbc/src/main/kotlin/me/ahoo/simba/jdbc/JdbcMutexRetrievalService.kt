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

import lombok.extern.slf4j.Slf4j
import me.ahoo.simba.core.AbstractMutexRetrievalService
import me.ahoo.simba.core.ContendPeriod.Companion.nextContenderDelay
import me.ahoo.simba.core.MutexRetriever
import me.ahoo.simba.util.Threads.defaultFactory
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Jdbc Mutex Retrieval Service.
 *
 * @author ahoo wang
 */
@Slf4j
class JdbcMutexRetrievalService(
    mutexRetriever: MutexRetriever,
    handleExecutor: Executor,
    private val mutexOwnerRepository: MutexOwnerRepository,
    private val initialDelay: Duration,
    private val ttl: Duration
) : AbstractMutexRetrievalService(mutexRetriever, handleExecutor) {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcMutexRetrievalService::class.java)
    }

    private var executorService: ScheduledThreadPoolExecutor? = null
    private var contendScheduledFuture: ScheduledFuture<*>? = null
    override fun startRetrieval() {
        executorService = ScheduledThreadPoolExecutor(1, defaultFactory("JdbcMutexRetrievalService"))
        nextSchedule(initialDelay.toMillis())
    }

    private fun nextSchedule(nextDelay: Long) {
        if (log.isDebugEnabled) {
            log.debug("nextSchedule - mutex:[{}] - nextDelay:[{}].", mutex, nextDelay)
        }
        contendScheduledFuture = executorService!!.schedule({ safeRetrieval() }, nextDelay, TimeUnit.MILLISECONDS)
    }

    override fun stopRetrieval() {
        contendScheduledFuture?.cancel(true)
        executorService?.shutdown()
    }

    private fun safeRetrieval() {
        try {
            val mutexOwner = mutexOwnerRepository.ensureOwner(mutex)
            notifyOwner(mutexOwner)
                .whenComplete { _: Void?, err: Throwable? ->
                    if (err != null) {
                        if (log.isErrorEnabled) {
                            log.error(err.message, err)
                        }
                    }
                    val nextDelay = nextContenderDelay(mutexOwner)
                    nextSchedule(nextDelay)
                }
        } catch (throwable: Throwable) {
            if (log.isErrorEnabled) {
                log.error(throwable.message, throwable)
            }
            nextSchedule(ttl.toMillis())
        }
    }
}
