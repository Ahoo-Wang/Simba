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

import io.lettuce.core.api.async.BaseRedisAsyncCommands
import me.ahoo.simba.redis.ChannelListener
import me.ahoo.simba.redis.RedisScripting
import me.ahoo.simba.redis.ReplicaWait
import me.ahoo.simba.redis.ScriptCall
import me.ahoo.simba.redis.ScriptReplies
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.ReturnType

/**
 * [RedisScripting] over a Spring Data Redis Lettuce connection factory.
 *
 * Only Lettuce is supported, and the constructor rejects any other factory (for example Jedis): a Jedis
 * subscription blocks the subscribing thread, and `WAIT` uses the Lettuce API.
 *
 * Scripts run with `EVALSHA`, falling back to `EVAL` when the server lacks the script. Several calls are pipelined
 * on one connection, except on a Redis Cluster, where Spring Data Redis does not pipeline, and before a `WAIT`: then
 * they run one after the other. `WAIT` runs through Lettuce's native connection that ran the scripts; with the shared
 * native connection it holds up other commands on it until the replicas acknowledge or the timeout passes.
 *
 * Subscriptions use a dedicated connection each, through [RedisConnection.pSubscribe], which returns once the server
 * confirmed the subscription.
 */
class SpringRedisScripting(private val connectionFactory: RedisConnectionFactory) : RedisScripting {
    init {
        // Checked by name, so a factory without Lettuce on the classpath fails here rather than with a linkage error.
        val lettuce = generateSequence<Class<*>>(connectionFactory.javaClass) { it.superclass }
            .any { it.name == LETTUCE_CONNECTION_FACTORY }
        require(lettuce) {
            "SpringRedisScripting needs a $LETTUCE_CONNECTION_FACTORY, was ${connectionFactory.javaClass.name}: " +
                "other drivers (such as Jedis) block on subscribe and lack the Lettuce WAIT API."
        }
    }

    override val clustered: Boolean by lazy {
        connectionFactory.connection.use { it is RedisClusterConnection }
    }

    override fun eval(calls: List<ScriptCall>, wait: ReplicaWait?): ScriptReplies =
        connectionFactory.connection.use { connection ->
            if (calls.size > 1 && wait == null && connection !is RedisClusterConnection) {
                ScriptReplies(connection.pipelined(calls))
            } else {
                val replies = calls.map { connection.evalScript(it) }
                ScriptReplies(replies, wait?.let { connection.waitReplicas(it) })
            }
        }

    override fun subscribe(pattern: String, listener: ChannelListener): AutoCloseable {
        val connection = connectionFactory.connection
        try {
            connection.pSubscribe(
                MessageListener { message, _ ->
                    listener.onMessage(message.channel.toString(Charsets.UTF_8), message.body.toString(Charsets.UTF_8))
                },
                pattern.toByteArray(Charsets.UTF_8)
            )
        } catch (error: RuntimeException) {
            connection.close()
            throw error
        }
        return AutoCloseable {
            try {
                connection.subscription?.close()
            } finally {
                connection.close()
            }
        }
    }

    private fun RedisConnection.evalScript(call: ScriptCall): List<Any?> {
        val keysAndArgs = call.keysAndArgs()
        val reply = try {
            scriptingCommands().evalSha<Any>(call.script.sha1, ReturnType.MULTI, call.keys.size, *keysAndArgs)
        } catch (error: RuntimeException) {
            if (!error.isNoScript()) {
                throw error
            }
            scriptingCommands().eval<Any>(call.source(), ReturnType.MULTI, call.keys.size, *keysAndArgs)
        }
        return decode(reply)
    }

    /** Pipelines the calls, with `EVAL`: a pipeline cannot fall back per call. */
    private fun RedisConnection.pipelined(calls: List<ScriptCall>): List<List<Any?>> {
        openPipeline()
        val results = try {
            calls.forEach {
                scriptingCommands().eval<Any>(it.source(), ReturnType.MULTI, it.keys.size, *it.keysAndArgs())
            }
            closePipeline()
        } catch (error: RuntimeException) {
            if (isPipelined) {
                closePipeline()
            }
            throw error
        }
        return results.map(::decode)
    }

    private fun RedisConnection.waitReplicas(wait: ReplicaWait): Int {
        val commands = nativeConnection
        check(
            commands is BaseRedisAsyncCommands<*, *>
        ) { "WAIT needs a Lettuce connection, was ${commands.javaClass}." }
        return commands.waitForReplication(wait.replicas, wait.timeout.toMillis()).get().toInt()
    }

    private fun ScriptCall.source(): ByteArray = script.source.toByteArray(Charsets.UTF_8)

    private fun ScriptCall.keysAndArgs(): Array<ByteArray> =
        (keys + args).map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()

    private fun decode(reply: Any?): List<Any?> =
        (checkNotNull(reply) { "No script reply." } as List<*>).map {
            when (it) {
                is ByteArray -> it.toString(Charsets.UTF_8)
                else -> it
            }
        }

    private fun Throwable.isNoScript(): Boolean =
        generateSequence(this) { it.cause }.any { it.message?.contains("NOSCRIPT") == true }

    private companion object {
        const val LETTUCE_CONNECTION_FACTORY =
            "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    }
}
