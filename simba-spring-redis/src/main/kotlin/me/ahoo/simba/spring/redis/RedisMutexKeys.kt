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
 * Redis key and channel names of a mutex. Must stay aligned with the Lua scripts, which derive
 * `simba:{mutex}`, `simba:{mutex}:contender` and `simba:{mutex}:{contenderId}` from `KEYS[1]`.
 *
 * @author ahoo wang
 */
internal class RedisMutexKeys(mutex: String) {
    /**
     * Script keys: the hash-tagged mutex, keeping all derived keys in one cluster slot.
     */
    val keys: List<String> = listOf("{$mutex}")

    /**
     * Lease key, also the channel that announces acquisitions.
     */
    val mutexKey: String = "${Simba.SIMBA}:${keys.single()}"

    /**
     * Channel on which a releasing owner wakes this queued contender.
     */
    fun contenderChannel(contenderId: String): String = "$mutexKey:$contenderId"
}
