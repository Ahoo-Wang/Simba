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
package me.ahoo.simba.partition

import java.time.Duration

/**
 * Who is alive in a group (ADR 0009): members join and refresh with [heartbeat] and expire after its `ttl`, measured
 * in backend time. Backends that track liveness otherwise (Zookeeper sessions) ignore `ttl`.
 *
 * @author ahoo wang
 */
interface MemberRegistry {
    /**
     * Joins [group] as [memberId], or refreshes the membership, until [ttl] from now.
     */
    fun heartbeat(group: String, memberId: String, ttl: Duration)

    /**
     * Leaves [group] at once; a no-op when [memberId] is not a member.
     */
    fun leave(group: String, memberId: String)

    /**
     * The live members of [group], sorted.
     */
    fun members(group: String): List<String>
}
