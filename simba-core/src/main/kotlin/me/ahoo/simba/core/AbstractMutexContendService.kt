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
import java.util.concurrent.Executor

/**
 * Abstract Mutex Contend Service.
 *
 * @author ahoo wang
 */
abstract class AbstractMutexContendService(
    override val contender: MutexContender,
    handleExecutor: Executor,
    /**
     * Receives this service's contention events; see [ContendObserver].
     */
    val observer: ContendObserver
) : AbstractMutexRetrievalService(
    contender,
    handleExecutor
),
    MutexContendService {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    constructor(contender: MutexContender, handleExecutor: Executor) :
        this(contender, handleExecutor, ContendObserver.NOOP)

    override fun startRetrieval() {
        resetOwner()
        startContend()
    }

    override fun stopRetrieval() {
        stopContend()
    }

    override fun onStateApplied(mutexState: MutexState) {
        if (mutexState.isAcquired(contenderId)) {
            observe { onAcquired(mutex) }
        }
        if (mutexState.isReleased(contenderId)) {
            observe { onReleased(mutex) }
        }
    }

    /**
     * Reports to [observer]; an observer failure is logged and never affects contention.
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun observe(event: ContendObserver.() -> Unit) {
        try {
            observer.event()
        } catch (error: Throwable) {
            log.warn(error) { "observe - mutex:[$mutex] contenderId:[$contenderId] - observer failed." }
        }
    }

    protected abstract fun startContend()
    protected abstract fun stopContend()
}
