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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.core.ContenderIdGenerator
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.schedule.ScheduleContext
import me.ahoo.simba.schedule.SimbaScheduler
import me.ahoo.simba.util.Threads
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spreads [partitions] over the live members of [group] (ADR 0009): each node runs [work] for the partitions that
 * rendezvous assignment gives it, each on its own mutex `{group}.{partition}` with its own fencing token.
 *
 * ```kotlin
 * SimbaPartitionedScheduler("billing", 16, factory, registry, ScheduleConfig.delay(Duration.ZERO, Duration.ofMinutes(1))) {
 *     billing.run(shard = it.partition, fencingToken = it.fencingToken)
 * }.start()
 * ```
 *
 * The node heartbeats every `membershipTtl / 3`, then recomputes its partitions: newly assigned ones start a
 * [SimbaScheduler], no longer assigned ones are stopped (work ends before the lease is released). Views of different
 * nodes may differ until their next heartbeat; the partition mutex keeps a partition to one owner meanwhile.
 *
 * @param memberId this node's identity in the group, unique per scheduler instance.
 * @param membershipTtl how long a membership lives without a heartbeat (ignored by Zookeeper).
 * @param worker thread name prefix of the work and membership threads.
 *
 * @author ahoo wang
 */
@Suppress("LongParameterList")
class SimbaPartitionedScheduler @JvmOverloads constructor(
    val group: String,
    val partitions: Int,
    private val contendServiceFactory: MutexContendServiceFactory,
    private val memberRegistry: MemberRegistry,
    val config: ScheduleConfig,
    val membershipTtl: Duration = DEFAULT_MEMBERSHIP_TTL,
    val memberId: String = ContenderIdGenerator.HOST.generate(),
    val worker: String = group,
    private val work: PartitionedWork
) : AutoCloseable {
    companion object {
        private val log = KotlinLogging.logger {}

        @JvmField
        val DEFAULT_MEMBERSHIP_TTL: Duration = Duration.ofSeconds(15)
        private const val HEARTBEATS_PER_TTL = 3L

        /**
         * The mutex of [partition] in [group].
         */
        @JvmStatic
        fun mutexOf(group: String, partition: Int): String = "$group.$partition"
    }

    init {
        require(group.isNotBlank()) { "group must not be blank." }
        require(partitions > 0) { "partitions must be positive: $partitions" }
        require(!membershipTtl.isNegative && !membershipTtl.isZero) {
            "membershipTtl must be positive: $membershipTtl"
        }
    }

    private val lock = Any()
    private val schedulers: List<SimbaScheduler> = (0 until partitions).map { partition ->
        SimbaScheduler(mutexOf(group, partition), contendServiceFactory, config, "$worker-$partition") { context ->
            work.work(PartitionContextImpl(context, group, partition, partitions))
        }
    }

    /**
     * Latest member view waiting for [rebalance]; coalesces views that arrive while a rebalance runs.
     */
    private val pendingMembers = AtomicReference<List<String>?>()

    @Volatile
    private var membership: ScheduledExecutorService? = null

    @Volatile
    private var rebalancer: ExecutorService? = null

    @Volatile
    var members: List<String> = emptyList()
        private set

    val running: Boolean
        get() = membership != null

    /**
     * Partitions this node should own in its current member view.
     */
    val assignedPartitions: Set<Int>
        get() = schedulers.indices.filterTo(linkedSetOf()) { schedulers[it].running }

    /**
     * Partitions this node owns right now (it leads their mutex).
     */
    val ownedPartitions: Set<Int>
        get() = schedulers.indices.filterTo(linkedSetOf()) { schedulers[it].isLeader }

    /**
     * Joins the group (failing when the registry is unreachable) and starts heartbeating and rebalancing.
     */
    fun start() {
        synchronized(lock) {
            check(membership == null) { "group:[$group] memberId:[$memberId] is already running." }
            memberRegistry.heartbeat(group, memberId, membershipTtl)
            rebalancer = Executors.newSingleThreadExecutor(Threads.defaultFactory("$worker-rebalance", daemon = true))
            membership = Executors.newSingleThreadScheduledExecutor(
                Threads.defaultFactory("$worker-membership", daemon = true)
            ).also {
                val period = membershipTtl.toNanos() / HEARTBEATS_PER_TTL
                it.scheduleWithFixedDelay(::heartbeat, 0, period, TimeUnit.NANOSECONDS)
            }
        }
    }

    /**
     * Stops every partition (each hands over as [SimbaScheduler.stop] does), then leaves the group.
     */
    @Suppress("TooGenericExceptionCaught")
    fun stop() {
        val stoppedRebalancer = synchronized(lock) {
            val heartbeats = checkNotNull(membership) { "group:[$group] memberId:[$memberId] is not running." }
            membership = null
            heartbeats.shutdownNow()
            rebalancer.also { rebalancer = null }
        }
        stoppedRebalancer?.shutdown()
        stoppedRebalancer?.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        synchronized(lock) {
            schedulers.filter { it.running }.forEach { it.stop() }
            members = emptyList()
        }
        try {
            memberRegistry.leave(group, memberId)
        } catch (error: Exception) {
            log.warn(error) { "stop - group:[$group] memberId:[$memberId] - leave failed, membership expires instead." }
        }
    }

    /**
     * Idempotent: stops when running.
     */
    override fun close() {
        if (running) {
            stop()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun heartbeat() {
        try {
            memberRegistry.heartbeat(group, memberId, membershipTtl)
            val live = memberRegistry.members(group)
            val view = if (memberId in live) live else (live + memberId).sorted()
            if (pendingMembers.getAndSet(view) == null) {
                rebalancer?.execute(::rebalance)
            }
        } catch (error: Throwable) {
            // Keep the current partitions: their mutexes still guard them, and a stale view only delays moves.
            log.warn(error) { "heartbeat - group:[$group] memberId:[$memberId] - failed, keeping current partitions." }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rebalance() {
        val view = pendingMembers.getAndSet(null) ?: return
        synchronized(lock) {
            if (membership == null) {
                return
            }
            members = view
            val assigned = PartitionAssignment.partitionsOf(memberId, partitions, view)
            schedulers.forEachIndexed { partition, scheduler ->
                try {
                    val wanted = partition in assigned
                    if (wanted && !scheduler.running) {
                        scheduler.start()
                    } else if (!wanted && scheduler.running) {
                        scheduler.stop()
                    }
                } catch (error: Throwable) {
                    log.error(error) {
                        "rebalance - group:[$group] memberId:[$memberId] partition:[$partition] - failed."
                    }
                }
            }
            log.info { "rebalance - group:[$group] memberId:[$memberId] - members:$view partitions:$assigned." }
        }
    }

    private class PartitionContextImpl(
        context: ScheduleContext,
        override val group: String,
        override val partition: Int,
        override val partitions: Int
    ) : PartitionContext, ScheduleContext by context
}
