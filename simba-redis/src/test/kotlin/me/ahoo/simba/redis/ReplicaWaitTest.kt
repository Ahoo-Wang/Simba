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

import me.ahoo.simba.spring.redis.SpringRedisScripting
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisSentinelConnection
import java.time.Duration

class ReplicaWaitTest {
    @Test
    fun aSubMillisecondTimeoutIsRejectedBecauseWaitZeroBlocksForever() {
        assertThrows<IllegalArgumentException> { ReplicaWait(1, Duration.ofNanos(500_000)) }
        assertThrows<IllegalArgumentException> { ReplicaWait(1, Duration.ZERO) }
        assertThrows<IllegalArgumentException> { ReplicaWait(1, Duration.ofMillis(-1)) }
        assertThrows<IllegalArgumentException> { ReplicaWait(0, Duration.ofSeconds(1)) }
    }

    @Test
    fun aTimeoutOfOneMillisecondIsAccepted() {
        ReplicaWait(1, ReplicaWait.MIN_TIMEOUT).timeout.assert().isEqualTo(Duration.ofMillis(1))
    }

    @Test
    fun springRedisScriptingRejectsAFactoryOtherThanLettuce() {
        val error = assertThrows<IllegalArgumentException> { SpringRedisScripting(OtherConnectionFactory) }

        error.message.assert().contains("LettuceConnectionFactory")
    }

    @Test
    fun springRedisScriptingAcceptsALettuceFactory() {
        SpringRedisScripting(RedisLeaseFixture.connectionFactory)
    }

    /** Stands in for a non-Lettuce driver such as Jedis; never connects. */
    private object OtherConnectionFactory : RedisConnectionFactory {
        override fun getConnection(): RedisConnection = throw UnsupportedOperationException()

        override fun getClusterConnection(): RedisClusterConnection = throw UnsupportedOperationException()

        override fun getConvertPipelineAndTxResults(): Boolean = false

        override fun getSentinelConnection(): RedisSentinelConnection = throw UnsupportedOperationException()

        override fun translateExceptionIfPossible(ex: RuntimeException): DataAccessException? = null
    }
}
