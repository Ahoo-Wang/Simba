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

import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId

/**
 * Key names of the Redis lease backend (ADR 0010 §7.2). The part in braces is the Redis Cluster hash tag: a session's
 * keys share one slot, each lease and each group has its own.
 */
internal object RedisLeaseKeys {
    private const val LEASE_PREFIX = "simba:lease:{"

    /** Pattern of every lease channel; a lease publishes on the channel named like its key. */
    const val LEASE_CHANNELS = "simba:lease:*"

    /** Hash `{session, token, expires}`; never expires, so the token never resets. */
    fun lease(name: LeaseName): String = "$LEASE_PREFIX$name}"

    /** The lease announced on [channel], or `null` for any other channel. */
    fun leaseOf(channel: String): LeaseName? {
        if (!channel.startsWith(LEASE_PREFIX) || !channel.endsWith("}")) {
            return null
        }
        return runCatching { LeaseName(channel.substring(LEASE_PREFIX.length, channel.length - 1)) }.getOrNull()
    }

    /** Hash recording the session; expires with it (PX) and serves diagnostics only. */
    fun session(id: SessionId): String = "simba:session:{$id}"

    /** Set of the lease keys the session tried to acquire, freed when it closes. */
    fun sessionLeases(id: SessionId): String = "simba:session:{$id}:leases"

    /** Set of the group keys the session joined, extended by keep-alive. */
    fun sessionGroups(id: SessionId): String = "simba:session:{$id}:groups"

    /** Sorted set of member session ids, scored by expiry in Redis time. */
    fun group(group: String): String = "simba:group:{$group}"
}

/** Redis Cluster hash slots: CRC16 (XMODEM) of the key's hash tag, modulo 16384. */
internal object RedisSlots {
    const val SLOTS = 16384
    private const val POLYNOMIAL = 0x1021
    private const val HIGH_BIT = 0x8000
    private const val MASK = 0xFFFF
    private const val BYTE = 0xFF
    private const val BITS = 8

    fun slot(key: String): Int {
        val bytes = key.toByteArray(Charsets.UTF_8)
        var start = 0
        var end = bytes.size
        val open = bytes.indexOf('{'.code.toByte())
        if (open >= 0) {
            val close = (open + 1 until bytes.size).firstOrNull { bytes[it] == '}'.code.toByte() }
            if (close != null && close > open + 1) {
                start = open + 1
                end = close
            }
        }
        var crc = 0
        for (index in start until end) {
            crc = crc xor ((bytes[index].toInt() and BYTE) shl BITS)
            repeat(BITS) {
                crc = if ((crc and HIGH_BIT) != 0) (crc shl 1) xor POLYNOMIAL else (crc shl 1)
            }
            crc = crc and MASK
        }
        return crc and (SLOTS - 1)
    }
}
