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
 * Mutex State.
 *
 * @author ahoo wang
 */
data class MutexState(
    /**
     * 旧的持有者.
     */
    val before: MutexOwner,
    /**
     * 新的持有者.
     */
    val after: MutexOwner
) {

    companion object {
        val NONE = MutexState(MutexOwner.NONE, MutexOwner.NONE)
    }

    val isChanged: Boolean
        get() = !before.isOwner(after.ownerId)

    fun isAcquired(contenderId: String): Boolean {
        return isChanged && isOwner(contenderId)
    }

    fun isReleased(contenderId: String): Boolean {
        return isChanged && before.isOwner(contenderId)
    }

    fun isOwner(contenderId: String): Boolean {
        return after.isOwner(contenderId)
    }

    fun isInTtl(contenderId: String): Boolean {
        return isOwner(contenderId) && after.isInTtl(contenderId)
    }
}
