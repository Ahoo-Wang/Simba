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

import me.ahoo.simba.Simba

/**
 * Redis key and channel names of a mutex, the single Kotlin source of the naming shared with the Lua scripts
 * and with other Simba nodes. All names share the `{mutex}` hash tag, so they live in one cluster slot.
 *
 * @author ahoo wang
 */
internal class RedisMutexKeys(mutex: String) {
    /**
     * Lease key, also the channel that announces acquisitions and releases.
     */
    val mutexKey: String = "${Simba.SIMBA}:{$mutex}"

    /**
     * Fencing counter, incremented once per ownership term; never expires.
     */
    val fenceKey: String = "$mutexKey:fence"

    /**
     * Fencing token of the current term; expires with the lease.
     */
    val tokenKey: String = "$mutexKey:token"
}
