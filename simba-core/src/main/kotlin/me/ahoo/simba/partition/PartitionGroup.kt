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

import me.ahoo.simba.Schedule
import me.ahoo.simba.Term
import java.util.concurrent.CompletionStage

/**
 * Partitioned leadership (ADR 0010 §6, ADR 0009): the client session joins the group, and every partition in
 * `0 until partitions` is an election named `"{name}.{partition}"`. Members come from the backend's live members
 * of the group, refreshed on membership hints and every renew interval; each node contends only for the partitions
 * that [PartitionAssignment] (rendezvous hashing over session ids) assigns to it in its latest view, and hands over
 * (work first, then the lease) the partitions it no longer owns.
 */
interface PartitionGroup {
    /**
     * The group name: a `String`, unlike `Election.name`, because it names a backend membership group
     * (`LeaseBackend.join` / `members` take a `String`), not a lease. It follows the [me.ahoo.simba.spi.LeaseName]
     * rules all the same, since every partition's lease is named `"{name}.{partition}"`.
     */
    val name: String
    val partitions: Int

    /** Runs [work] on [schedule] for every partition this node leads; see [me.ahoo.simba.Election.schedule]. */
    fun schedule(schedule: Schedule, work: PartitionWork): GroupTask

    /** Runs [work] once per term of every partition this node leads; see [me.ahoo.simba.Election.runWhileLeader]. */
    fun runWhileLeader(work: PartitionWork): GroupTask
}

/** Work bound to one term of one partition. */
fun interface PartitionWork {
    @Throws(Exception::class)
    fun run(term: Term, partition: Int)
}

/** A running [PartitionGroup]: stopping it leaves the group and stops every partition election. */
interface GroupTask : AutoCloseable {
    val group: PartitionGroup

    /** The partitions whose elections this node runs in its latest view (led or contended). */
    fun partitions(): Set<Int>

    /** Leaves the group and stops every partition; completes once each one handed over. */
    fun stop(): CompletionStage<Void>

    /** [stop] and wait, except inside a leadership callback or leader work. */
    override fun close()
}
