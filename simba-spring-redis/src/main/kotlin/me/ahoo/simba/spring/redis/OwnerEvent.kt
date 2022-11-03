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

/**
 * Owner Event.
 *
 * @author ahoo wang
 */
data class OwnerEvent(val event: String, val ownerId: String, val eventAt: Long = System.currentTimeMillis()) {

    companion object {
        const val EVENT_RELEASED = "released"
        const val EVENT_ACQUIRED = "acquired"
        const val DELIMITER = "@@"
        fun of(message: String): OwnerEvent {
            val msgs = message.split(DELIMITER.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            check(msgs.size == 2) {
                "Incorrect message format:[$message]"
            }
            return OwnerEvent(msgs[0], msgs[1])
        }
    }
}
