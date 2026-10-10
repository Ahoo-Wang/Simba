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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.core.MutexRetrievalService.Status
import me.ahoo.simba.util.SequentialExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
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
        private val log = KotlinLogging.logger {}
        private val STATUS: AtomicReferenceFieldUpdater<AbstractMutexRetrievalService, Status> =
            AtomicReferenceFieldUpdater.newUpdater(
                AbstractMutexRetrievalService::class.java,
                Status::class.java,
                MutexRetrievalService::status.name
            )
    }

    @Volatile
    final override var status = Status.INITIAL
        private set

    @Volatile
    override var mutexState: MutexState = MutexState.NONE
        protected set

    /**
     * Guards lifecycle transitions and the read-before/write of [mutexState]. Never held while user callbacks run:
     * callbacks are serialized by [notifyExecutor] instead, which keeps them in submission order.
     */
    private val stateLock = Any()
    private val notifyExecutor = SequentialExecutor(handleExecutor)
    private val lifecycleGeneration = AtomicLong()

    /**
     * Whether the current thread is dispatching a notification of this service (a callback is running).
     */
    private val dispatching = ThreadLocal.withInitial { false }

    /**
     * Generation of the current lifecycle, incremented by every [start].
     */
    protected val currentGeneration: Long
        get() = lifecycleGeneration.get()

    protected fun resetOwner() {
        synchronized(stateLock) {
            mutexState = MutexState.NONE
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun start() {
        log.info {
            "start - mutex:[${retriever.mutex}] - status:[$status]"
        }
        synchronized(stateLock) {
            check(STATUS.compareAndSet(this, Status.INITIAL, Status.STARTING)) {
                "Cannot start from state [$status]. Expected: [${Status.INITIAL}]"
            }
            lifecycleGeneration.incrementAndGet()
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
        val generation = lifecycleGeneration.get()
        return CompletableFuture.runAsync({ dispatch(newOwner, generation) }, notifyExecutor)
    }

    private fun dispatch(newOwner: MutexOwner, generation: Long) {
        val outer = dispatching.get()
        dispatching.set(true)
        try {
            applyAndNotify(newOwner, generation)
        } finally {
            dispatching.set(outer)
        }
    }

    /**
     * Applies [newOwner] under [stateLock], then invokes the retriever outside it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun applyAndNotify(newOwner: MutexOwner, generation: Long) {
        val newState = synchronized(stateLock) {
            if (generation != lifecycleGeneration.get()) {
                log.warn {
                    "applyAndNotify - ignore - mutex:[${retriever.mutex}] - newOwner:[$newOwner] belongs to a previous lifecycle."
                }
                return
            }
            /*
             * A notification submitted while active may execute while stopping or after stop() completes.
             * Once inactive, only a release notification (NONE) may still be applied —
             * the stop-release contract depends on it — while any ownership claim is stale.
             */
            if (!status.isActive && newOwner.ownerId.isNotBlank()) {
                log.warn {
                    "applyAndNotify - ignore - mutex:[${retriever.mutex}] - newOwner:[$newOwner] is not active[$status]."
                }
                return
            }
            MutexState(afterOwner, newOwner).also { mutexState = it }
        }
        try {
            retriever.notifyOwner(newState)
        } catch (throwable: Throwable) {
            log.error(throwable) {
                "applyAndNotify error - mutex:[$retriever] - newOwner:[$newOwner]"
            }
        }
    }

    /**
     * Delivers the stop release (NONE) and returns once its callback ran, in order after earlier notifications.
     * From inside a callback of this service (e.g. `onAcquired` calling `stop()`), it runs inline instead of
     * waiting for the very drain it is part of.
     */
    private fun deliverRelease(generation: Long) {
        if (dispatching.get()) {
            applyAndNotify(MutexOwner.NONE, generation)
            return
        }
        notifyExecutor.executeAndWait { dispatch(MutexOwner.NONE, generation) }
    }

    override fun stop() {
        check(tryStop()) {
            "Cannot stop mutex:[${retriever.mutex}] from state:[$status]. Expected:[${Status.RUNNING}]"
        }
    }

    /**
     * Stops the service when it is [Status.RUNNING]; returns `false` without side effects otherwise.
     */
    private fun tryStop(): Boolean {
        log.info {
            "stop - mutex:[${retriever.mutex}] - status:[$status]"
        }
        synchronized(stateLock) {
            if (!STATUS.compareAndSet(this, Status.RUNNING, Status.STOPPING)) {
                return false
            }
        }
        try {
            stopRetrieval()
        } finally {
            deliverRelease(lifecycleGeneration.get())
            STATUS.set(this, Status.INITIAL)
        }
        return true
    }

    /**
     * Idempotent: stops the service when running and is a no-op otherwise.
     */
    @Throws(Exception::class)
    override fun close() {
        tryStop()
    }
}
