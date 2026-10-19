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
import me.ahoo.simba.tck.FencingSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

/**
 * The Lua recipe from the correctness guide: one script compares the token and writes atomically.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RedisFencingRecipeTest : FencingSpec() {
    companion object {
        private val FENCED_SET = RedisScript.of(
            """
            local highest = tonumber(redis.call('get', KEYS[1]) or '0')
            local token = tonumber(ARGV[1])
            if token < highest then
                return 0
            end
            redis.call('set', KEYS[1], ARGV[1])
            redis.call('set', KEYS[2], ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.java
        )
    }

    private val connectionFactory = RedisFixture.newConnectionFactory()
    private val redisTemplate = StringRedisTemplate(connectionFactory)

    override fun leaseBackend(): LeaseBackend = RedisLeaseBackend(RedisLeaseFixture.scripting)

    @AfterAll
    fun close() {
        connectionFactory.destroy()
    }

    override fun write(resource: String, fencingToken: Long): Boolean {
        val keys = listOf("{$resource}:token", "{$resource}:value")
        return redisTemplate.execute(FENCED_SET, keys, fencingToken.toString(), "written by $fencingToken") == 1L
    }

    override fun highestToken(resource: String): Long {
        return redisTemplate.opsForValue().get("{$resource}:token")?.toLong() ?: 0
    }
}
