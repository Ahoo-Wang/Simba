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

import java.time.Duration

/**
 * Validated lease timing shared by polling backends.
 *
 * @param ttl time after acquisition at which the owner renews; must be at least 1ms.
 * @param transition grace period after [ttl] during which only the owner may renew.
 * @param initialDelay delay before the first contention after start.
 *
 * @author ahoo wang
 */
data class LeaseConfig(
    val ttl: Duration,
    val transition: Duration,
    val initialDelay: Duration = Duration.ZERO
) {
    init {
        require(!initialDelay.isNegative) { "initialDelay must not be negative: $initialDelay" }
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive: $ttl" }
        require(!transition.isNegative) { "transition must not be negative: $transition" }
    }

    val initialDelayMillis: Long = initialDelay.toMillisExact("initialDelay")
    val ttlMillis: Long = ttl.toMillisExact("ttl")
    val transitionMillis: Long = transition.toMillisExact("transition")

    /**
     * Total lease length (`ttl + transition`) in milliseconds.
     */
    val leaseMillis: Long

    init {
        require(ttlMillis > 0) { "ttl must be at least 1ms: $ttl" }
        leaseMillis = try {
            Math.addExact(ttlMillis, transitionMillis)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("ttl + transition must fit in milliseconds", error)
        }
    }
}

private fun Duration.toMillisExact(name: String): Long {
    return try {
        toMillis()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$name must fit in milliseconds: $this", error)
    }
}
