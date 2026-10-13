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
package me.ahoo.simba.test

import me.ahoo.simba.partition.MemberRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the TCK against a minimal in-memory registry, so the spec itself is exercised without a backend.
 */
class InMemoryMemberRegistryTest : MemberRegistrySpec() {
    override val memberRegistry: MemberRegistry = object : MemberRegistry {
        private val expiries = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

        override fun heartbeat(group: String, memberId: String, ttl: Duration) {
            expiries.computeIfAbsent(group) { ConcurrentHashMap() }[memberId] = System.nanoTime() + ttl.toNanos()
        }

        override fun leave(group: String, memberId: String) {
            expiries[group]?.remove(memberId)
        }

        override fun members(group: String): List<String> {
            val now = System.nanoTime()
            return expiries[group].orEmpty().filterValues { it - now > 0 }.keys.sorted()
        }
    }
}
