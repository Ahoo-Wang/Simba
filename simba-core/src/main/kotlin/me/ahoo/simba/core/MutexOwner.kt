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
 * Mutex Owner.
 *
 * @author ahoo wang
 */
open class MutexOwner @JvmOverloads constructor(
    /**
     * 持有者Id.
     */
    val ownerId: String,
    /**
     * 获取到互斥锁的时间戳.
     */
    val acquiredAt: Long = System.currentTimeMillis(),
    /**
     * 互斥锁的生存期（Time To Live）.
     * [java.util.concurrent.TimeUnit.MILLISECONDS]
     */
    val ttlAt: Long = Long.MAX_VALUE,
    /**
     * 缓冲期/过渡期（绝对时间）
     * 1. 为了使领导权稳定，当前领导者可以在过渡期内优先续期
     * 2. 用于缓冲领导者任务执行时间
     * [java.util.concurrent.TimeUnit.MILLISECONDS]
     */
    val transitionAt: Long = Long.MAX_VALUE,
    /**
     * Fencing token of this ownership term: strictly increasing across terms of the mutex and stable within one.
     * [NO_FENCING_TOKEN] when the backend does not issue tokens. See ADR 0002.
     */
    val fencingToken: Long = NO_FENCING_TOKEN
) {

    fun isOwner(contenderId: String): Boolean {
        return ownerId == contenderId
    }

    open val currentAt: Long
        get() = System.currentTimeMillis()
    val isInTtl: Boolean
        get() = ttlAt > currentAt

    fun isInTtl(contenderId: String): Boolean {
        return isOwner(contenderId) && isInTtl
    }

    /**
     * 判断是否在过渡期内.
     *
     * @return boolean
     */
    val isInTransition: Boolean
        get() = transitionAt >= currentAt

    fun isInTransitionOf(contenderId: String): Boolean {
        return isOwner(contenderId) &&
            isInTransition
    }

    /**
     * 判断 是否当前存在领导者 ([transitionAt] >= [currentAt]).
     *
     * @return boolean
     */
    fun hasOwner(): Boolean {
        return transitionAt >= currentAt
    }

    companion object {
        const val NONE_OWNER_ID = ""
        const val NO_FENCING_TOKEN = 0L

        @JvmField
        val NONE = MutexOwner(NONE_OWNER_ID, 0, 0, 0)
    }
}
