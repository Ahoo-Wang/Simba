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

import java.security.MessageDigest
import java.time.Duration

/**
 * The two things [RedisLeaseBackend] needs from a Redis driver: running Lua scripts and pattern subscriptions.
 *
 * Keeps the lease logic independent of any client library; `SpringRedisScripting` adapts Spring Data Redis.
 */
interface RedisScripting {
    /** Whether the server is a Redis Cluster, so keys of different hash slots must go to different script calls. */
    val clustered: Boolean get() = false

    /**
     * Runs [calls] in order, pipelined where the driver can, and answers their replies in the same order. Each reply
     * is the script's array reply, with integers as [Long] and bulk strings as [String].
     *
     * With [wait], the adapter then runs `WAIT` on the connection that ran the scripts and reports the number of
     * replicas that acknowledged them in [ScriptReplies.replicas].
     */
    fun eval(calls: List<ScriptCall>, wait: ReplicaWait? = null): ScriptReplies

    /**
     * Subscribes [listener] to the channels matching [pattern] and returns once the subscription is active; closing
     * the result unsubscribes.
     */
    fun subscribe(pattern: String, listener: ChannelListener): AutoCloseable
}

/** A message published on [channel]. */
fun interface ChannelListener {
    fun onMessage(channel: String, message: String)
}

/** A Lua script and its SHA-1 digest, for `EVALSHA`. */
class LeaseScript(val name: String, val source: String) {
    val sha1: String = MessageDigest.getInstance("SHA-1")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun toString(): String = name

    companion object {
        internal fun load(name: String): LeaseScript {
            val resource = "me/ahoo/simba/redis/$name.lua"
            val source = checkNotNull(LeaseScript::class.java.classLoader.getResourceAsStream(resource)) {
                "Script [$resource] not found."
            }.use { it.readBytes().toString(Charsets.UTF_8) }
            return LeaseScript(name, source)
        }
    }
}

/** One script invocation; all [keys] belong to one hash slot on a Redis Cluster. */
data class ScriptCall(val script: LeaseScript, val keys: List<String>, val args: List<String>)

/** Replies of [RedisScripting.eval], plus the replicas that acknowledged the writes when a wait was requested. */
data class ScriptReplies(val replies: List<List<Any?>>, val replicas: Int? = null)

/**
 * After acquiring, wait until [replicas] replicas acknowledged the writes, at most [timeout] (`WAIT`). A new term
 * that is not acknowledged in time is released and the acquisition fails, so a failover is less likely to lose it.
 * [timeout] is at least 1 ms and is sent in whole milliseconds.
 */
data class ReplicaWait(val replicas: Int, val timeout: Duration) {
    init {
        require(replicas >= 1) { "replicas must be at least 1: $replicas" }
        // WAIT takes whole milliseconds and `WAIT n 0` blocks forever, so a sub-millisecond timeout is rejected.
        require(timeout >= MIN_TIMEOUT) { "timeout must be at least $MIN_TIMEOUT: $timeout" }
    }

    companion object {
        /** Smallest timeout `WAIT` can express. */
        @JvmField
        val MIN_TIMEOUT: Duration = Duration.ofMillis(1)
    }
}
