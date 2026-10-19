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

package me.ahoo.simba

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.engine.EngineConfig
import me.ahoo.simba.engine.EngineShell
import me.ahoo.simba.engine.InternalSimbaApi
import me.ahoo.simba.engine.ScheduleBody
import me.ahoo.simba.engine.Scheduler
import me.ahoo.simba.engine.TermBody
import me.ahoo.simba.engine.VirtualThreads
import me.ahoo.simba.engine.WorkListener
import me.ahoo.simba.engine.runReported
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.event.SimbaSnapshot
import me.ahoo.simba.partition.GroupTask
import me.ahoo.simba.partition.PartitionAssignment
import me.ahoo.simba.partition.PartitionGroup
import me.ahoo.simba.partition.PartitionWork
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Simba 5 client (ADR 0010): one per process, one backend session, every election, lock and group attached to
 * that session.
 *
 * [close] closes the session, so the backend frees every lease at once.
 */
@OptIn(InternalSimbaApi::class)
class Simba private constructor(builder: Builder) : AutoCloseable {
    private val ownedExecutors = ArrayList<ExecutorService>()

    /** The session timing this client runs with. */
    val config: SimbaConfig = builder.config
    private val ticker: Ticker = builder.scheduler ?: builder.ticker
    private val shell: EngineShell
    private val workExecutor: Executor
    private val running = ConcurrentHashMap<LeaseName, Run>()
    private val groups = ConcurrentHashMap.newKeySet<String>()
    private val closing = AtomicBoolean(false)
    private val closed = CompletableFuture<Void>()

    /** One started lifecycle of an election name; [stopped] completes once its stop completed. */
    private class Run {
        val stopped = CompletableFuture<Void>()
    }

    init {
        val backend = requireNotNull(builder.backend) { "backend is required." }
        shell = EngineShell(
            backend = backend,
            config = EngineConfig.of(config),
            ticker = ticker,
            ioExecutor = builder.ioExecutor ?: VirtualThreads.newExecutor("simba-io").also(ownedExecutors::add),
            callbackExecutor = builder.callbackExecutor
                ?: VirtualThreads.newExecutor("simba-callback").also(ownedExecutors::add),
            eventListeners = builder.listeners.toList(),
            seed = builder.seed ?: ThreadLocalRandom.current().nextLong(),
            scheduler = builder.scheduler,
        )
        workExecutor = builder.workExecutor ?: VirtualThreads.newExecutor("simba-work").also(ownedExecutors::add)
        shell.boot()
    }

    /**
     * The election for [name]. Handles of the same name share one election; nothing is kept per name once it is
     * stopped, so one-off names do not accumulate.
     */
    fun election(name: String): Election {
        checkOpen()
        return ShellElection(LeaseName(name))
    }

    /** The lock on [name]; a lock and an election of the same name share one lease. */
    fun lock(name: String): DistributedLock {
        checkOpen()
        return ShellLock(LeaseName(name))
    }

    /**
     * The partition group [name] with [partitions] partitions, elected as `"{name}.{partition}"`; see
     * [PartitionGroup].
     */
    fun group(name: String, partitions: Int): PartitionGroup {
        checkOpen()
        require(partitions > 0) { "partitions must be positive, was $partitions." }
        LeaseName(name)
        LeaseName(partitionName(name, partitions - 1))
        return ShellGroup(name, partitions)
    }

    /**
     * Waits until the engine has nothing queued and returns how many loop tasks it handled so far; only meaningful
     * with a [Scheduler] and a direct I/O executor.
     */
    @InternalSimbaApi
    fun awaitIdle(): Long = shell.idle().join()

    /** What the engine keeps, as `name=count` pairs; every count is zero once nothing is started. */
    @InternalSimbaApi
    fun footprint(): Map<String, Int> {
        val footprint = shell.footprint().join()
        return mapOf(
            "elections" to footprint.elections,
            "works" to footprint.works,
            "pending" to footprint.pending,
            "dispatchers" to footprint.dispatchers,
            "groups" to footprint.groups + groups.size,
            "terms" to footprint.terms,
            "abandoned" to footprint.abandoned,
            "running" to running.size,
        )
    }

    /** The session and every started election as of now (ADR 0010 §9); never blocks on the backend. */
    fun snapshot(): SimbaSnapshot = shell.snapshot().join()

    internal fun dispatcherCount(): Int = shell.footprint().join().dispatchers

    /** Revokes every term, closes the session and stops the engine; completes after the last callback ran. */
    fun closeAsync(): CompletionStage<Void> {
        if (closing.compareAndSet(false, true)) {
            shell.close().whenComplete { _, error ->
                ownedExecutors.forEach(ExecutorService::shutdown)
                if (error == null) closed.complete(null) else closed.completeExceptionally(error)
            }
        }
        return closed.minimalCompletionStage()
    }

    /** [closeAsync] and wait, except inside a leadership callback or leader work, where waiting would wait for itself. */
    override fun close() {
        join(closeAsync())
    }

    private fun checkOpen() {
        check(!closing.get()) { "Simba is closed." }
    }

    /** Waits for [stage] unless the caller is a callback or leader work of this client. */
    private fun join(stage: CompletionStage<Void>) {
        if (!EngineShell.isDispatching() && !WorkListener.isWorking()) {
            stage.toCompletableFuture().join()
        }
    }

    /** Claims [name] for a new lifecycle; `null` when it is already started. */
    private fun claim(name: LeaseName): Run? {
        checkOpen()
        val run = Run()
        return if (running.putIfAbsent(name, run) == null) run else null
    }

    /** Stops [name] if [run] (or any run, when null) is the current lifecycle; completes when it stopped. */
    private fun stopRun(name: LeaseName, run: Run?): CompletableFuture<Void> {
        var stopping: Pair<Run, CompletableFuture<Void>>? = null
        // Post the stop while the name is still taken: a claim in this client waits on the map entry, so its start
        // always reaches the engine after this stop and is never dropped by it. `shell.stop` only enqueues.
        running.computeIfPresent(name) { _, current ->
            if (run != null && current !== run) {
                current
            } else {
                stopping = current to shell.stop(name)
                null
            }
        }
        val (stopped, stop) = stopping ?: return run?.stopped ?: CompletableFuture.completedFuture(null)
        stop.whenComplete { _, _ -> stopped.stopped.complete(null) }
        return stopped.stopped
    }

    private fun startWork(name: LeaseName, executor: Executor?, body: TermBody): LeaderTask {
        val election = ShellElection(name)
        check(claim(name) != null) { "Election [$name] is already started." }
        shell.start(name, WorkListener(shell, executor ?: workExecutor, ticker, body), work = true)
        return ElectionTask(election)
    }

    private fun leaderBody(work: LeaderWork) = TermBody { term, _ -> runReported(ticker, term, work) }

    private fun scheduleBody(schedule: Schedule, work: LeaderWork) = ScheduleBody(shell, ticker, schedule, work)

    private inner class ShellElection(override val name: LeaseName) : Election {
        override fun start(listener: LeadershipListener) {
            check(claim(name) != null) { "Election [$name] is already started." }
            shell.start(name, listener)
        }

        override fun stop(): CompletionStage<Void> = stopRun(name, null).minimalCompletionStage()

        override fun stepDown(cooldown: Duration): CompletionStage<Void> {
            require(!cooldown.isNegative) { "cooldown must not be negative." }
            return shell.stepDown(name, cooldown.toNanos()).minimalCompletionStage()
        }

        override fun runWhileLeader(executor: Executor?, work: LeaderWork): LeaderTask =
            startWork(name, executor, leaderBody(work))

        override fun schedule(schedule: Schedule, work: LeaderWork): LeaderTask =
            startWork(name, null, scheduleBody(schedule, work))
    }

    private inner class ElectionTask(override val election: Election) : LeaderTask {
        override fun stop(): CompletionStage<Void> = election.stop()

        override fun close() {
            join(stop())
        }
    }

    // region locks

    private inner class ShellLock(override val name: LeaseName) : DistributedLock {
        override fun acquire(timeout: Duration): LockTerm {
            require(!timeout.isNegative) { "timeout must not be negative." }
            return acquire(timeout, probe = false) ?: throw LockTimeoutException(name, timeout)
        }

        override fun tryAcquire(): LockTerm? = acquire(config.leaseDuration, probe = true)

        private fun acquire(timeout: Duration, probe: Boolean): LockTerm? {
            val deadline = CompletableFuture<Void>()
            shell.schedule(ticker.nanoTime() + timeout.toNanos()) { deadline.complete(null) }
            val run = claimLocally(probe, deadline) ?: return null
            val listener = LockListener(name, run)
            val lost = if (probe) CompletableFuture<Void>() else null
            shell.start(name, listener, probe = lost)
            try {
                awaitAny(listener.elected, lost, deadline, closed)
            } catch (error: InterruptedException) {
                stopRun(name, run)
                throw error
            }
            val term = listener.elected.getNow(null)
            if (term == null || !term.isValid) {
                stopRun(name, run)
                checkOpen()
                return null
            }
            return ShellLockTerm(term) { stopRun(name, run) }
        }

        /** Claims the name in this client, waiting for a current holder here unless [probe]; `null` on timeout. */
        private fun claimLocally(probe: Boolean, deadline: CompletableFuture<Void>): Run? {
            while (true) {
                claim(name)?.let { return it }
                if (probe) {
                    return null
                }
                val holder = running[name] ?: continue
                awaitAny(holder.stopped, deadline, closed)
                checkOpen()
                if (deadline.isDone) {
                    return null
                }
            }
        }
    }

    /** Takes the first term; a term ending other than by release releases the lock for good. */
    private inner class LockListener(private val name: LeaseName, private val run: Run) : LeadershipListener {
        val elected = CompletableFuture<Term>()

        override fun onElected(term: Term) {
            elected.complete(term)
        }

        override fun onRevoked(term: Term, reason: RevokeReason) {
            if (reason != RevokeReason.STOPPED) {
                stopRun(name, run)
            }
        }
    }

    private inner class ShellLockTerm(
        private val term: Term,
        private val release: () -> CompletableFuture<Void>,
    ) : LockTerm, Term by term {
        private val released by lazy { release() }

        override fun releaseAsync(): CompletionStage<Void> = released.minimalCompletionStage()

        override fun close() {
            join(releaseAsync())
        }

        override fun toString(): String = "LockTerm($term)"
    }

    private fun awaitAny(vararg futures: CompletableFuture<*>?) {
        CompletableFuture.anyOf(*futures.filterNotNull().toTypedArray()).get()
    }

    // endregion

    // region groups

    private inner class ShellGroup(override val name: String, override val partitions: Int) : PartitionGroup {
        override fun schedule(schedule: Schedule, work: PartitionWork): GroupTask =
            start { election, partition -> election.schedule(schedule) { term -> work.run(term, partition) } }

        override fun runWhileLeader(work: PartitionWork): GroupTask =
            start { election, partition -> election.runWhileLeader { term -> work.run(term, partition) } }

        private fun start(startPartition: (Election, Int) -> LeaderTask): GroupTask {
            checkOpen()
            check(groups.add(name)) { "Group [$name] is already started." }
            val task = GroupRun(this, startPartition)
            shell.join(name, task::onView)
            return task
        }

        override fun toString(): String = "PartitionGroup(name=$name, partitions=$partitions)"
    }

    /** Starts and stops partition elections as views change; views arrive on the event loop and never block. */
    private inner class GroupRun(
        override val group: ShellGroup,
        private val startPartition: (Election, Int) -> LeaderTask,
    ) : GroupTask {
        private val lock = Any()
        private val tasks = TreeMap<Int, LeaderTask>()
        private var stopped: CompletableFuture<Void>? = null

        fun onView(session: SessionId?, members: List<SessionId>) {
            synchronized(lock) {
                if (stopped != null) {
                    return
                }
                val mine = if (session == null || session !in members) {
                    emptySet()
                } else {
                    PartitionAssignment.partitionsOf(session.value, group.partitions, members.map { it.value })
                }
                (tasks.keys - mine).forEach { partition -> tasks.remove(partition)?.stop() }
                (mine - tasks.keys).forEach { partition ->
                    val name = LeaseName(partitionName(group.name, partition))
                    try {
                        tasks[partition] = startPartition(ShellElection(name), partition)
                    } catch (error: IllegalStateException) {
                        log.warn(error) { "Partition [$name] of group [${group.name}] could not start." }
                    }
                }
            }
        }

        override fun partitions(): Set<Int> = synchronized(lock) { tasks.keys.toSet() }

        override fun stop(): CompletionStage<Void> {
            val all = synchronized(lock) {
                stopped?.let { return it.minimalCompletionStage() }
                val stops = tasks.values.map { it.stop().toCompletableFuture() }
                tasks.clear()
                CompletableFuture.allOf(*stops.toTypedArray()).also { stopped = it }
            }
            shell.leave(group.name)
            all.whenComplete { _, _ -> groups.remove(group.name) }
            return all.minimalCompletionStage()
        }

        override fun close() {
            join(stop())
        }
    }

    private fun partitionName(group: String, partition: Int): String = "$group.$partition"

    // endregion

    class Builder internal constructor() {
        internal var backend: LeaseBackend? = null
        internal var config: SimbaConfig = SimbaConfig()
        internal var ticker: Ticker = Ticker.SYSTEM
        internal var scheduler: Scheduler? = null
        internal var ioExecutor: Executor? = null
        internal var callbackExecutor: Executor? = null
        internal var workExecutor: Executor? = null
        internal val listeners = ArrayList<SimbaEventListener>()
        internal var seed: Long? = null

        fun backend(backend: LeaseBackend) = apply { this.backend = backend }

        fun config(config: SimbaConfig) = apply { this.config = config }

        /** Runs backend calls; default one virtual thread per call. */
        fun ioExecutor(executor: Executor) = apply { ioExecutor = executor }

        /** Runs leadership callbacks, serially per election; default as [ioExecutor]. */
        fun callbackExecutor(executor: Executor) = apply { callbackExecutor = executor }

        /** Runs leader work, one task per term; default one virtual thread per task. */
        fun workExecutor(executor: Executor) = apply { workExecutor = executor }

        fun listener(listener: SimbaEventListener) = apply { listeners += listener }

        /** Must advance at the rate of [System.nanoTime]: the event loop schedules its timers with it. */
        fun ticker(ticker: Ticker) = apply { this.ticker = ticker }

        /** Clock and timers together, replacing [ticker] and the event loop's timers; for virtual time in tests. */
        @InternalSimbaApi
        fun scheduler(scheduler: Scheduler) = apply { this.scheduler = scheduler }

        /** Seed of the retry jitter. */
        fun seed(seed: Long) = apply { this.seed = seed }

        fun build(): Simba = Simba(this)
    }

    companion object {
        private val log = KotlinLogging.logger {}

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
