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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class PartitionAssignmentTest {
    @Test
    fun `weights are a stable contract across versions`() {
        PartitionAssignment.weight(0, "a").assert().isEqualTo(-312225022020847644L)
        PartitionAssignment.weight(7, "node-1").assert().isEqualTo(-6755311613153260892L)
        PartitionAssignment.weight(15, "0:42@10.0.0.1").assert().isEqualTo(5071331791018506376L)
        (0 until 8).map { PartitionAssignment.ownerOf(it, listOf("a", "b", "c")) }
            .assert().containsExactly("c", "b", "a", "c", "b", "b", "c", "c")
    }

    @Test
    fun `the owner does not depend on member order`() {
        (0 until 64).forEach { partition ->
            PartitionAssignment.ownerOf(partition, listOf("a", "b", "c"))
                .assert().isEqualTo(PartitionAssignment.ownerOf(partition, listOf("c", "a", "b")))
        }
    }

    @Test
    fun `no members own nothing`() {
        PartitionAssignment.ownerOf(0, emptyList()).assert().isNull()
        PartitionAssignment.partitionsOf("a", 4, emptyList()).assert().isEmpty()
    }

    @Test
    fun `partitions spread evenly and every partition has exactly one owner`() {
        val members = (1..4).map { "node-$it" }
        val shares = members.map { PartitionAssignment.partitionsOf(it, 1024, members) }

        shares.flatten().sorted().assert().isEqualTo((0 until 1024).toList())
        shares.forEach { it.size.assert().isBetween(256 - 64, 256 + 64) }
    }

    @Test
    fun `a joining member takes partitions only from others, never shuffles the rest`() {
        val before = listOf("node-1", "node-2", "node-3")
        val after = before + "node-4"

        val moved = (0 until 1024).filter {
            PartitionAssignment.ownerOf(it, before) != PartitionAssignment.ownerOf(it, after)
        }

        moved.forEach { PartitionAssignment.ownerOf(it, after).assert().isEqualTo("node-4") }
        moved.size.assert().isBetween(256 - 64, 256 + 64)
    }
}
