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
package me.ahoo.simba.spring.redis

import me.ahoo.simba.core.MutexOwner

/**
 * Acquire Result.
 *
 * @author ahoo wang
 */
data class AcquireResult(val ownerId: String, val transitionAt: Long) {

    companion object {
        val NONE = AcquireResult(MutexOwner.NONE_OWNER_ID, 0L)

        /**
         * build [AcquireResult] from resultStr.
         *
         * @param resultStr {ownerId}:{transitionAt}.
         * @return AcquireResult
         */
        fun of(resultStr: String): AcquireResult {
            if (OwnerEvent.DELIMITER == resultStr) {
                return NONE
            }
            val msgs: Array<String> =
                resultStr.split(OwnerEvent.DELIMITER.toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            check(msgs.size == 2) { "Incorrect resultStr format:[$resultStr]" }
            val ownerId = msgs[0]
            val keyTtl = msgs[1].toLong()
            val transitionAt = System.currentTimeMillis() + keyTtl
            return AcquireResult(ownerId, transitionAt)
        }
    }
}
