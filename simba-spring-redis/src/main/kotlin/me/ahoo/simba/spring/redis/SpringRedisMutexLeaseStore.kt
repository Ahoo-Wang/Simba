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

import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.MutexLeaseStore
import me.ahoo.simba.core.MutexOwner
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

/**
 * [MutexLeaseStore] backed by the `mutex_*.lua` scripts.
 *
 * @author ahoo wang
 */
internal class SpringRedisMutexLeaseStore(private val redisTemplate: StringRedisTemplate) : MutexLeaseStore {
    companion object {
        private val SCRIPT_ACQUIRE = RedisScript.of(ClassPathResource("mutex_acquire.lua"), List::class.java)
        private val SCRIPT_GUARD = RedisScript.of(ClassPathResource("mutex_guard.lua"), List::class.java)
        private val SCRIPT_RELEASE = RedisScript.of(ClassPathResource("mutex_release.lua"), Boolean::class.java)
    }

    override fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner {
        val keys = RedisMutexKeys(mutex)
        val (script, scriptKeys) = if (renew) {
            SCRIPT_GUARD to listOf(keys.mutexKey, keys.tokenKey)
        } else {
            SCRIPT_ACQUIRE to listOf(keys.mutexKey, keys.fenceKey, keys.tokenKey)
        }
        val reply = redisTemplate.execute(
            script,
            scriptKeys,
            contenderId,
            config.leaseMillis.toString()
        )
        return AcquireResult.of(checkNotNull(reply) { "No script reply for mutex:[$mutex]" }).toMutexOwner(config)
    }

    override fun release(mutex: String, contenderId: String): Boolean {
        val keys = RedisMutexKeys(mutex)
        return redisTemplate.execute(
            SCRIPT_RELEASE,
            listOf(keys.mutexKey, keys.tokenKey),
            contenderId
        )
    }
}

/**
 * Rebuilds the owner timeline from the lease end ([transitionAt]) reported by Redis.
 */
internal fun leaseOwner(
    ownerId: String,
    transitionAt: Long,
    config: LeaseConfig,
    fencingToken: Long = MutexOwner.NO_FENCING_TOKEN
): MutexOwner {
    val ttlAt = transitionAt - config.transitionMillis
    val acquiredAt = ttlAt - config.ttlMillis
    return MutexOwner(ownerId, acquiredAt, ttlAt, transitionAt, fencingToken)
}

internal fun AcquireResult.toMutexOwner(config: LeaseConfig): MutexOwner =
    leaseOwner(ownerId, transitionAt, config, fencingToken)
