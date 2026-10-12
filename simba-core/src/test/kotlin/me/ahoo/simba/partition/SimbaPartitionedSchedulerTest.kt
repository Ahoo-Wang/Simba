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

import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaPartitionedSchedulerTest {
    private val factory = LocalMutexFactory()
    private val registry = LocalMemberRegistry()
    private val runs = LinkedBlockingQueue<Pair<String, Int>>()
    private val schedulers = mutableListOf<SimbaPartitionedScheduler>()

    @AfterEach
    fun stopAll() {
        schedulers.forEach { it.close() }
    }

    private fun node(memberId: String, partitions: Int = 8): SimbaPartitionedScheduler {
        return SimbaPartitionedScheduler(
            group = "billing",
            partitions = partitions,
            contendServiceFactory = factory,
            memberRegistry = registry,
            config = ScheduleConfig.rate(Duration.ZERO, Duration.ofMillis(20)),
            membershipTtl = Duration.ofMillis(150),
            memberId = memberId
        ) { context ->
            context.group.assert().isEqualTo("billing")
            context.mutex.assert().isEqualTo("billing.${context.partition}")
            context.fencingToken.assert().isGreaterThan(0)
            runs.add(memberId to context.partition)
        }.also { schedulers += it }
    }

    /**
     * Waits until every partition ran on the member that owns it in [members]; runs of earlier views are skipped.
     */
    private fun awaitRunsMatching(members: List<String>, partitions: Int = 8) {
        val expected = (0 until partitions).associateWith { PartitionAssignment.ownerOf(it, members)!! }
        val seen = ConcurrentHashMap.newKeySet<Int>()
        while (seen.size < partitions) {
            val (member, partition) = checkNotNull(runs.poll(10, TimeUnit.SECONDS)) {
                "partitions $seen ran as expected, the others did not within 10s"
            }
            if (expected[partition] == member) {
                seen += partition
            }
        }
    }

    @Test
    fun `a single node runs every partition`() {
        val single = node("a")
        single.start()

        awaitRunsMatching(listOf("a"))
        single.ownedPartitions.assert().isEqualTo((0 until 8).toSet())
        single.members.assert().containsExactly("a")
    }

    @Test
    fun `two nodes split the partitions by rendezvous assignment`() {
        val a = node("a")
        val b = node("b")
        a.start()
        b.start()

        awaitRunsMatching(listOf("a", "b"))
        a.assignedPartitions.assert().isEqualTo(PartitionAssignment.partitionsOf("a", 8, listOf("a", "b")))
        b.assignedPartitions.assert().isEqualTo(PartitionAssignment.partitionsOf("b", 8, listOf("a", "b")))
    }

    @Test
    fun `a stopped node hands its partitions to the others`() {
        val a = node("a")
        val b = node("b")
        a.start()
        b.start()
        awaitRunsMatching(listOf("a", "b"))

        b.stop()
        runs.clear()

        awaitRunsMatching(listOf("a"))
        b.ownedPartitions.assert().isEmpty()
        registry.members("billing").assert().containsExactly("a")
    }

    @Test
    fun `a restarted node rejoins`() {
        val a = node("a")
        a.start()
        awaitRunsMatching(listOf("a"))
        a.stop()
        a.running.assert().isFalse()
        runs.clear()

        a.start()

        awaitRunsMatching(listOf("a"))
    }

    @Test
    fun `invalid configuration and lifecycle misuse fail fast`() {
        assertThrows<IllegalArgumentException> { node("a", partitions = 0) }
        val a = node("a")
        assertThrows<IllegalStateException> { a.stop() }
        a.start()
        assertThrows<IllegalStateException> { a.start() }
        SimbaPartitionedScheduler.mutexOf("g", 3).assert().isEqualTo("g.3")
    }
}
