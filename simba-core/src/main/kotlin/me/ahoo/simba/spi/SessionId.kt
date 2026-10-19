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

package me.ahoo.simba.spi

import java.security.SecureRandom

/**
 * Identity of one backend session incarnation. Never reused: an expired session is never revived, a new one gets a
 * new id. An immutable value with equality by [value]; a plain class so that Java can use it.
 */
class SessionId(val value: String) : Comparable<SessionId> {
    init {
        require(value.isNotEmpty()) { "Session id must not be empty." }
    }

    override fun compareTo(other: SessionId): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is SessionId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private val random = SecureRandom()

        /** A random 128-bit id as 32 lowercase hex characters. */
        @JvmStatic
        fun random(): SessionId {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return SessionId(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}
