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
package me.ahoo.simba.partition

/**
 * Rendezvous (highest random weight) assignment of partitions to members (ADR 0009): every node computes the same
 * owner from the same members, and a member joining or leaving moves only about `1 / members` of the partitions.
 *
 * The weight is a 64-bit FNV-1a hash of `"$partition@$member"` in UTF-8 mixed with SplitMix64. It is part of the
 * contract: nodes of different versions must compute the same weights.
 *
 * @author ahoo wang
 */
object PartitionAssignment {
    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val BYTE_MASK = 0xff
    private const val MIX_SHIFT_1 = 30
    private const val MIX_SHIFT_2 = 27
    private const val MIX_SHIFT_3 = 31
    private const val MIX_MULTIPLIER_1 = -0x40a7b892e31b1a47L
    private const val MIX_MULTIPLIER_2 = -0x6b2fb644ecceee15L

    /**
     * The member that should own [partition]; `null` without members. Ties, practically impossible, go to the
     * smaller member id.
     */
    @JvmStatic
    fun ownerOf(partition: Int, members: Collection<String>): String? {
        return members.maxWithOrNull(compareBy<String> { weight(partition, it) }.thenByDescending { it })
    }

    /**
     * The partitions in `0 until partitions` that [member] should own among [members].
     */
    @JvmStatic
    fun partitionsOf(member: String, partitions: Int, members: Collection<String>): Set<Int> {
        return (0 until partitions).filterTo(linkedSetOf()) { ownerOf(it, members) == member }
    }

    /**
     * The rendezvous weight of [member] for [partition].
     */
    @JvmStatic
    fun weight(partition: Int, member: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in "$partition@$member".toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toInt() and BYTE_MASK).toLong()) * FNV_PRIME
        }
        return mix(hash)
    }

    private fun mix(value: Long): Long {
        var z = value
        z = (z xor (z ushr MIX_SHIFT_1)) * MIX_MULTIPLIER_1
        z = (z xor (z ushr MIX_SHIFT_2)) * MIX_MULTIPLIER_2
        return z xor (z ushr MIX_SHIFT_3)
    }
}
