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

import me.ahoo.simba.core.MutexRetrievalService.Status
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

/**
 * Abstract Mutex Retrieval Service.
 *
 * @author ahoo wang
 */
abstract class AbstractMutexRetrievalService protected constructor(
    override val retriever: MutexRetriever,
    protected val handleExecutor: Executor
) : MutexRetrievalService {
    companion object {
        private val log = LoggerFactory.getLogger(AbstractMutexRetrievalService::class.java)
        val STATUS: AtomicReferenceFieldUpdater<AbstractMutexRetrievalService, Status> =
            AtomicReferenceFieldUpdater.newUpdater(
                AbstractMutexRetrievalService::class.java,
                Status::class.java,
                MutexRetrievalService::status.name
            )
    }

    @Volatile
    override var status = Status.INITIAL

    @Volatile
    override var mutexState: MutexState = MutexState.NONE
        protected set

    protected fun resetOwner() {
        mutexState = MutexState.NONE
    }

    @Suppress("TooGenericExceptionCaught")
    override fun start() {
        if (log.isInfoEnabled) {
            log.info("start - mutex:[{}] - status:[{}]", retriever.mutex, status)
        }
        check(STATUS.compareAndSet(this, Status.INITIAL, Status.STARTING)) {
            "Cannot start from state [$status]. Expected: [${Status.INITIAL}]"
        }
        try {
            startRetrieval()
            STATUS.set(this, Status.RUNNING)
        } catch (error: Throwable) {
            STATUS.set(this, Status.INITIAL)
            throw error
        }
    }

    protected abstract fun startRetrieval()
    protected abstract fun stopRetrieval()
    protected fun notifyOwner(newOwner: MutexOwner): CompletableFuture<Void> {
        return CompletableFuture.runAsync({ safeNotifyOwner(newOwner) }, handleExecutor)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun safeNotifyOwner(newOwner: MutexOwner) {
        try {
            /*
             * Concurrency issues.
             * Order of assignment is very important.
             */
            val newState = MutexState(afterOwner, newOwner)
            mutexState = newState
            retriever.notifyOwner(newState)
        } catch (throwable: Throwable) {
            if (log.isErrorEnabled) {
                log.error(throwable.message, throwable)
            }
        }
    }

    override fun stop() {
        if (log.isInfoEnabled) {
            log.info("stop - mutex:[{}] - running:[{}]", retriever.mutex, status)
        }
        check(STATUS.compareAndSet(this, Status.RUNNING, Status.STOPPING)) {
            "Cannot stop mutex:[${retriever.mutex}] from state:[$status]. Expected:[${Status.RUNNING}]"
        }
        try {
            stopRetrieval()
        } finally {
            STATUS.set(this, Status.INITIAL)
        }
    }

    @Throws(Exception::class)
    override fun close() {
        stop()
    }
}
