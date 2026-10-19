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

/**
 * Name of a lease: 1–128 characters from `[A-Za-z0-9._:-]`.
 *
 * One rule for every backend, so no name means something different as a Redis key, a Zookeeper path or a JDBC
 * column (no `{}` hash tags, no `/` path separators).
 */
@JvmInline
value class LeaseName(val value: String) : Comparable<LeaseName> {
    init {
        require(value.length in 1..MAX_LENGTH) { "Lease name length must be in [1, $MAX_LENGTH], was ${value.length}." }
        require(value.all(::isAllowed)) { "Lease name [$value] must only contain [A-Za-z0-9._:-]." }
    }

    override fun compareTo(other: LeaseName): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 128

        private fun isAllowed(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '.' || char == '_' || char == ':' ||
                char == '-'
    }
}
