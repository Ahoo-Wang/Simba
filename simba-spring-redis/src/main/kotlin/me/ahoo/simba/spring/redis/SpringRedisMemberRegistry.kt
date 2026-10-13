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
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

/**
 * [MemberRegistry] on a Redis sorted set `simba:{group}:members` scored by expiry in Redis time (ADR 0009).
 *
 * @author ahoo wang
 */
class SpringRedisMemberRegistry(private val redisTemplate: StringRedisTemplate) : MemberRegistry {
    companion object {
        private val SCRIPT_HEARTBEAT = RedisScript.of(ClassPathResource("member_heartbeat.lua"), Long::class.java)
        private val SCRIPT_LIST = RedisScript.of(ClassPathResource("member_list.lua"), List::class.java)
    }

    override fun heartbeat(group: String, memberId: String, ttl: Duration) {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive: $ttl" }
        redisTemplate.execute(SCRIPT_HEARTBEAT, listOf(membersKeyOf(group)), memberId, ttl.toMillis().toString())
    }

    override fun leave(group: String, memberId: String) {
        redisTemplate.opsForZSet().remove(membersKeyOf(group), memberId)
    }

    override fun members(group: String): List<String> {
        val members = redisTemplate.execute(SCRIPT_LIST, listOf(membersKeyOf(group))) ?: return emptyList()
        return members.map { it.toString() }.sorted()
    }
}
