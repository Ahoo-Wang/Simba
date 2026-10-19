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

package me.ahoo.simba.redis

import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.tck.LeaseBackendSpec

/** [RedisLeaseBackend] on a single Redis 7 node passes the backend TCK: one script call per operation. */
class RedisLeaseBackendSpecTest : LeaseBackendSpec() {
    override fun createBackend(): LeaseBackend = RedisLeaseBackend(RedisLeaseFixture.scripting)
}

/**
 * [RedisLeaseBackend] routing by hash slot, as on a Redis Cluster, passes the backend TCK; every script call keeps to
 * one slot.
 */
class SlotRoutedRedisLeaseBackendSpecTest : LeaseBackendSpec() {
    override fun createBackend(): LeaseBackend =
        RedisLeaseBackend(SlotCheckingScripting(RedisLeaseFixture.scripting), routeBySlot = true)
}
