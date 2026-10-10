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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

class SpringRedisMutexLeaseStoreTest {
    private val config = LeaseConfig(Duration.ofSeconds(10), Duration.ofSeconds(6))
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val store = SpringRedisMutexLeaseStore(redisTemplate)

    @Test
    fun `acquire runs the acquire script with the full lease`() {
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), *anyVararg()) } returns
            "c1@@16000"

        val owner = store.contend("m", "c1", renew = false, config = config)

        owner.ownerId.assert().isEqualTo("c1")
        (owner.transitionAt - owner.ttlAt).assert().isEqualTo(6_000)
        (owner.ttlAt - owner.acquiredAt).assert().isEqualTo(10_000)
        verify {
            redisTemplate.execute(match<RedisScript<String>> { it.isScript("'nx'") }, listOf("{m}"), "c1", "16000")
        }
    }

    @Test
    fun `renew runs the guard script`() {
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), *anyVararg()) } returns
            "c1@@16000"

        store.contend("m", "c1", renew = true, config = config)

        verify {
            redisTemplate.execute(match<RedisScript<String>> { it.isScript("'xx'") }, listOf("{m}"), "c1", "16000")
        }
    }

    @Test
    fun `no owner maps to an empty owner id`() {
        every { redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>(), *anyVararg()) } returns "@@"

        store.contend("m", "c1", renew = true, config = config).ownerId.assert().isEmpty()
    }

    @Test
    fun `release runs the release script`() {
        every { redisTemplate.execute(any<RedisScript<Boolean>>(), listOf("{m}"), "c1") } returns true

        store.release("m", "c1").assert().isTrue()
    }

    private fun RedisScript<*>.isScript(marker: String): Boolean = scriptAsString.contains(marker)
}
