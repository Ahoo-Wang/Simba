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

import me.ahoo.simba.spring.redis.RedisFixture
import me.ahoo.simba.spring.redis.SpringRedisScripting
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.atomic.AtomicInteger

/** One connection factory over [RedisFixture] for the lease backend tests. */
object RedisLeaseFixture {
    val connectionFactory: LettuceConnectionFactory by lazy { RedisFixture.newConnectionFactory() }
    val scripting: SpringRedisScripting by lazy { SpringRedisScripting(connectionFactory) }
    val redis: StringRedisTemplate by lazy { StringRedisTemplate(connectionFactory) }
}

/**
 * Runs every call through [delegate] after checking that its keys share one hash slot, as a Redis Cluster requires,
 * and counts the script calls.
 */
class SlotCheckingScripting(private val delegate: RedisScripting) : RedisScripting by delegate {
    val calls = AtomicInteger()

    override fun eval(calls: List<ScriptCall>, wait: ReplicaWait?): ScriptReplies {
        calls.forEach { call ->
            val slots = call.keys.map(RedisSlots::slot).toSet()
            check(slots.size <= 1) { "Script [${call.script}] spans slots $slots: ${call.keys}" }
        }
        this.calls.addAndGet(calls.size)
        return delegate.eval(calls, wait)
    }
}
