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
    fun scriptingValuesCompareByTheirFields() {
        val script = LeaseScript("s", "return {}")
        val wait = ReplicaWait(1, Duration.ofSeconds(1))
        val call = ScriptCall(script, listOf("k"), listOf("a"))
        val replies = ScriptReplies(listOf(listOf(1L)), 2)

        wait.assert().isEqualTo(ReplicaWait(1, Duration.ofSeconds(1)))
        wait.hashCode().assert().isEqualTo(ReplicaWait(1, Duration.ofSeconds(1)).hashCode())
        wait.assert().isNotEqualTo(ReplicaWait(2, Duration.ofSeconds(1)))
        wait.toString().assert().isEqualTo("ReplicaWait(replicas=1, timeout=PT1S)")
        call.assert().isEqualTo(ScriptCall(script, listOf("k"), listOf("a")))
        call.hashCode().assert().isEqualTo(ScriptCall(script, listOf("k"), listOf("a")).hashCode())
        call.assert().isNotEqualTo(ScriptCall(script, listOf("k"), listOf("b")))
        call.toString().assert().isEqualTo("ScriptCall(script=s, keys=[k], args=[a])")
        replies.assert().isEqualTo(ScriptReplies(listOf(listOf(1L)), 2))
        replies.hashCode().assert().isEqualTo(ScriptReplies(listOf(listOf(1L)), 2).hashCode())
        replies.assert().isNotEqualTo(ScriptReplies(listOf(listOf(1L))))
        ScriptReplies(emptyList()).replicas.assert().isNull()
        replies.toString().assert().isEqualTo("ScriptReplies(replies=[[1]], replicas=2)")
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
