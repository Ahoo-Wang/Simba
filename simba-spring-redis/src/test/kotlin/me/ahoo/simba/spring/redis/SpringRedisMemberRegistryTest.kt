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

import me.ahoo.simba.partition.MemberRegistry
import me.ahoo.simba.test.MemberRegistrySpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.core.StringRedisTemplate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SpringRedisMemberRegistryTest : MemberRegistrySpec() {
    private val connectionFactory = RedisFixture.newConnectionFactory()
    override val memberRegistry: MemberRegistry = SpringRedisMemberRegistry(StringRedisTemplate(connectionFactory))

    @AfterAll
    fun close() {
        connectionFactory.destroy()
    }
}
