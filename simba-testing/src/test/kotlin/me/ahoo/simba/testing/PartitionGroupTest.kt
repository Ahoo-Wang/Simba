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

package me.ahoo.simba.testing

import me.ahoo.simba.Schedule
import me.ahoo.simba.Simba
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.partition.GroupTask
import me.ahoo.simba.partition.PartitionAssignment
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** `Simba.group` on virtual time: rendezvous assignment over live sessions, hand-over before release. */
class PartitionGroupTest {
    private val simba = VirtualSimba()
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val started = LinkedBlockingQueue<String>()

    @AfterEach
    fun close() {
        simba.close()
    }

    private fun join(client: Simba, label: String): GroupTask =
        client.group(GROUP, PARTITIONS).runWhileLeader { term, partition ->
            term.name.assert().isEqualTo(LeaseName("$GROUP.$partition"))
            log += "start $label $partition"
            started += "$label $partition"
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                log += "exit $label $partition"
            }
        }

    private fun expectStarts(label: String, partitions: Set<Int>) {
        val expected = partitions.mapTo(HashSet()) { "$label $it" }
        while (expected.isNotEmpty()) {
            simba.settle()
            val next = started.next()
            check(expected.remove(next)) { "unexpected start $next, still expecting $expected" }
        }
    }

    private fun expectExits(count: Int) {
        repeat(count) { simba.nextEvent<SimbaEvent.WorkCompleted>() }
        simba.settle()
    }

    /** The sessions in the group, mapped to the partitions rendezvous hashing gives each. */
    private fun assignment(): Map<String, Set<Int>> {
        val members = simba.backend.members(GROUP).map { it.value }
        return members.associateWith { PartitionAssignment.partitionsOf(it, PARTITIONS, members) }
    }

    private fun ownerSessions(partitions: Set<Int>): Set<String> =
        partitions.mapTo(HashSet()) { checkNotNull(simba.backend.owner(LeaseName("$GROUP.$it"))).session.value }

    @Test
    fun `members split the partitions by rendezvous and hand them over work first`() {
        val first = join(simba.client(), "A")
        expectStarts("A", (0 until PARTITIONS).toSet())

        val second = join(simba.client(), "B")
        simba.settle()
        val assignment = assignment()
        assignment.size.assert().isEqualTo(2)
        val firstShare = first.partitions()
        val secondShare = (0 until PARTITIONS).toSet() - firstShare
        assignment.values.toSet().assert().isEqualTo(setOf(firstShare, secondShare))
        expectExits(secondShare.size)
        expectStarts("B", secondShare)
        second.partitions().assert().isEqualTo(secondShare)
        ownerSessions(firstShare).size.assert().isEqualTo(1)
        ownerSessions(secondShare).size.assert().isEqualTo(1)
        for (partition in secondShare) {
            (log.indexOf("exit A $partition") < log.indexOf("start B $partition")).assert().isTrue()
        }

        second.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        expectStarts("A", secondShare)
        first.partitions().assert().isEqualTo((0 until PARTITIONS).toSet())
        simba.backend.members(GROUP).size.assert().isEqualTo(1)
    }

    @Test
    fun `a member whose session ends loses its partitions to the others`() {
        val first = simba.client()
        join(first, "A")
        expectStarts("A", (0 until PARTITIONS).toSet())
        val second = join(simba.client(), "B")
        simba.settle()
        expectExits(PARTITIONS - assignment().values.first { it != second.partitions() }.size)
        expectStarts("B", second.partitions())

        first.close()
        expectStarts("B", (0 until PARTITIONS).toSet() - second.partitions())
        second.partitions().size.assert().isEqualTo(PARTITIONS)
    }

    @Test
    fun `scheduled partitions run on their schedule`() {
        val client = simba.client()
        val runs = LinkedBlockingQueue<Int>()
        client.group("billing", 2).schedule(Schedule.fixedDelay(Duration.ofSeconds(1))) { term, partition ->
            term.ensureValid()
            runs += partition
        }
        simba.advance(Duration.ZERO)
        setOf(runs.next(), runs.next()).assert().isEqualTo(setOf(0, 1))
    }

    @Test
    fun `invalid groups are rejected`() {
        val client = simba.client()
        runCatching { client.group("bad/name", 1) }.isFailure.assert().isTrue()
        runCatching { client.group(GROUP, 0) }.isFailure.assert().isTrue()
        client.group(GROUP, 1).runWhileLeader { _, _ -> }
        runCatching { client.group(GROUP, 1).runWhileLeader { _, _ -> } }.isFailure.assert().isTrue()
    }

    companion object {
        const val GROUP = "orders"
        const val PARTITIONS = 8
    }
}
