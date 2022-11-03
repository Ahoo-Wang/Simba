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

/**
 * Mutex Retrieval Service.
 *
 * @author ahoo wang
 */
interface MutexRetrievalService : AutoCloseable {
    val retriever: MutexRetriever
    val mutex: String
        get() = retriever.mutex
    val mutexState: MutexState
    val beforeOwner: MutexOwner
        get() = mutexState.before

    /**
     * 当前持有者.
     * 与分布式锁资源保持弱一致性
     *
     * @return boolean
     */
    val afterOwner: MutexOwner
        get() = mutexState.after

    /**
     * 当前是否存在持有者.
     *
     * @return boolean
     */
    fun hasOwner(): Boolean {
        return afterOwner !== MutexOwner.NONE
    }

    val isRunning: Boolean
    fun start()
    fun stop()
}
