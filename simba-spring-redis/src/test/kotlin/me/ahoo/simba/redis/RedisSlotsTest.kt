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

import io.lettuce.core.cluster.SlotHash
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class RedisSlotsTest {
    @Test
    fun slotsMatchTheRedisClusterSpecification() {
        listOf("foo", "bar", "", "{user1000}.following", "foo{}{bar}", "foo{{bar}}zap", "foo{bar}{zap}", "{}", "ü{ä}")
            .forEach { RedisSlots.slot(it).assert().isEqualTo(SlotHash.getSlot(it)) }
        RedisSlots.slot("foo").assert().isEqualTo(12182)
    }

    @Test
    fun aSessionKeepsItsKeysInOneSlot() {
        val session = SessionId.random()

        setOf(
            RedisLeaseKeys.session(session),
            RedisLeaseKeys.sessionLeases(session),
            RedisLeaseKeys.sessionGroups(session)
        ).map(RedisSlots::slot).toSet().size.assert().isEqualTo(1)
    }

    @Test
    fun aLeaseChannelNamesItsLease() {
        val name = LeaseName("order-dispatcher.1")

        RedisLeaseKeys.leaseOf(RedisLeaseKeys.lease(name)).assert().isEqualTo(name)
        RedisLeaseKeys.leaseOf("simba:lease:{bad name}").assert().isNull()
        RedisLeaseKeys.leaseOf("simba:{mutex}").assert().isNull()
        RedisLeaseKeys.leaseOf("simba:lease:{open").assert().isNull()
    }
}
