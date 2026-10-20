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
import me.ahoo.simba.event.ElectionKind
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
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
    private val callbackExecutor: Executor
    private val running = ConcurrentHashMap<LeaseName, Run>()

    /** Orders every claim-and-start and remove-and-stop, so that a stop always reaches the loop before a restart. */
    private val lifecycle = Any()
    private val groups = ConcurrentHashMap.newKeySet<String>()
    private val closing = AtomicBoolean(false)
    private val closed = CompletableFuture<Void>()

    /** One started lifecycle of an election name; [stopped] completes once its stop completed. */
    private class Run {
        val stopped = CompletableFuture<Void>()
    }

    init {
        val backend = requireNotNull(builder.backend) { "backend is required." }
        callbackExecutor = builder.callbackExecutor
            ?: VirtualThreads.newExecutor("simba-callback").also(ownedExecutors::add)
        shell = EngineShell(
            backend = backend,
            config = EngineConfig.of(config),
            ticker = ticker,
            ioExecutor = builder.ioExecutor ?: VirtualThreads.newExecutor("simba-io").also(ownedExecutors::add),
            callbackExecutor = callbackExecutor,
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

    /**
     * Waits until every event published so far reached the listeners; for tests that read what a listener recorded
     * (events are delivered off the engine loop). Not from a callback or listener, nor on the loop.
     */
    @InternalSimbaApi
    fun awaitEvents() {
        EngineShell.checkNotOnLoop("awaitEvents()")
        shell.eventsDelivered().join()
    }

    /** [duration] in nanoseconds, saturated at `Long.MAX_VALUE` instead of overflowing. */
    private fun saturatedNanos(duration: Duration): Long =
        try {
            duration.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

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
            // Lock attempts wait on the close future; an abandoned or finished one must leave no node behind.
            "closeWaiters" to closed.numberOfDependents,
        )
    }

    /** The session and every started election as of now (ADR 0010 §9); never blocks on the backend. */
    fun snapshot(): SimbaSnapshot = shell.snapshot().join()

    internal fun dispatcherCount(): Int = shell.footprint().join().dispatchers

    /**
     * Revokes every term, closes the session and stops the engine; completes after the last callback and event ran.
     * Like every stage the client returns, it cannot be waited for on the event loop (see [Election.stop]).
     */
    fun closeAsync(): CompletionStage<Void> {
        if (closing.compareAndSet(false, true)) {
            shell.close().whenComplete { _, error ->
                // Every election ended with the session: forget the names and release anyone waiting on them.
                synchronized(lifecycle) {
                    running.values.forEach { it.stopped.complete(null) }
                    running.clear()
                }
                ownedExecutors.forEach(ExecutorService::shutdown)
                if (error == null) closed.complete(null) else closed.completeExceptionally(error)
            }
        }
        return closed.loopGuarded()
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
        // A stage already complete needs no wait, wherever the caller runs (on the loop too).
        if ((stage as? LoopGuardedStage<*>)?.isDone == true) {
            return
        }
        if (EngineShell.isDispatching() || WorkListener.isWorking()) {
            return
        }
        EngineShell.checkNotOnLoop("A blocking close()")
        stage.toCompletableFuture().join()
    }

    /**
     * Claims [name] for [run] and starts its election; `false` when the name is already started. Claim and start
     * happen under [lifecycle], like remove and stop in [stopRun], so the loop sees every stop before the next start.
     */
    private fun startRun(
        name: LeaseName,
        run: Run,
        listener: LeadershipListener,
        kind: ElectionKind,
        work: Boolean = false,
        probe: CompletableFuture<Void>? = null,
    ): Boolean =
        synchronized(lifecycle) {
            checkOpen()
            if (running.putIfAbsent(name, run) != null) {
                return false
            }
            shell.start(name, listener, work, probe, kind)
            true
        }

    /**
     * Stops [name] if [run] (or any run, when null) is the current lifecycle; completes when that run stopped. A run
     * that is no longer current is not touched: its stop already happened, or a later run owns the name.
     */
    private fun stopRun(name: LeaseName, run: Run?): CompletableFuture<Void> {
        val stopped = synchronized(lifecycle) {
            val current = if (run == null) running.remove(name) else run.takeIf { running.remove(name, it) }
            current?.also { stopping -> shell.stop(name).whenComplete { _, _ -> stopping.stopped.complete(null) } }
        }
        return (stopped ?: run)?.stopped ?: CompletableFuture.completedFuture(null)
    }

    private fun startWork(
        name: LeaseName,
        kind: ElectionKind,
        executor: Executor?,
        body: (ElectionKind) -> TermBody,
    ): LeaderTask {
        val run = Run()
        val listener = WorkListener(shell, executor ?: workExecutor, ticker, config.leaseDuration.toNanos(), body(kind))
        check(startRun(name, run, listener, kind, work = true)) { "Election [$name] is already started." }
        return ElectionTask(ShellElection(name, kind), run)
    }

    private fun leaderBody(work: LeaderWork): (ElectionKind) -> TermBody =
        { kind -> TermBody { term, _ -> runReported(ticker, term, work, kind) } }

    private fun scheduleBody(schedule: Schedule, work: LeaderWork): (ElectionKind) -> TermBody =
        { kind -> ScheduleBody(shell, ticker, schedule, work, kind) }

    private inner class ShellElection(
        override val name: LeaseName,
        private val kind: ElectionKind = ElectionKind.ELECTION,
    ) : Election {
        override fun start(listener: LeadershipListener) {
            check(startRun(name, Run(), listener, kind)) { "Election [$name] is already started." }
        }

        override fun stop(): CompletionStage<Void> = stopRun(name, null).loopGuarded()

        override fun stepDown(cooldown: Duration): CompletionStage<Void> {
            require(!cooldown.isNegative) { "cooldown must not be negative." }
            checkOpen()
            return shell.stepDown(name, saturatedNanos(cooldown)).loopGuarded()
        }

        override fun runWhileLeader(executor: Executor?, work: LeaderWork): LeaderTask =
            startWork(name, kind, executor, leaderBody(work))

        override fun schedule(schedule: Schedule, work: LeaderWork): LeaderTask =
            startWork(name, kind, null, scheduleBody(schedule, work))
    }

    /** Stops only its own run: closing it after another run took the name leaves that run alone. */
    private inner class ElectionTask(override val election: Election, private val run: Run) : LeaderTask {
        override fun stop(): CompletionStage<Void> = stopNow().loopGuarded()

        /** The stop as a plain future, for the client's own use on the loop (group views). */
        fun stopNow(): CompletableFuture<Void> = stopRun(election.name, run)

        override fun close() {
            join(stop())
        }
    }

    // region locks

    private inner class ShellLock(override val name: LeaseName) : DistributedLock {
        // The loop check comes before the attempt starts: an attempt nobody waits for would win a term nobody holds.
        @Throws(InterruptedException::class)
        override fun acquire(timeout: Duration): LockTerm {
            EngineShell.checkNotOnLoop("Acquiring a lock")
            require(!timeout.isNegative) { "timeout must not be negative." }
            return await(acquireAsync(timeout))
        }

        @Throws(InterruptedException::class)
        override fun tryAcquire(): LockTerm? {
            EngineShell.checkNotOnLoop("Acquiring a lock")
            return await(tryAcquireAsync())
        }

        override fun acquireAsync(timeout: Duration): CompletableFuture<LockTerm> {
            if (timeout.isNegative) {
                return CompletableFuture.failedFuture(IllegalArgumentException("timeout must not be negative."))
            }
            @Suppress("UNCHECKED_CAST")
            return LockAttempt(name, timeout, probe = false).result as CompletableFuture<LockTerm>
        }

        override fun tryAcquireAsync(): CompletableFuture<LockTerm?> =
            LockAttempt(name, config.leaseDuration, probe = true).result

        /** Waits for [future]; interrupting the caller cancels it, which abandons the attempt. */
        private fun <T> await(future: CompletableFuture<T>): T =
            try {
                future.get()
            } catch (error: InterruptedException) {
                future.cancel(false)
                throw error
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
    }

    /**
     * One acquisition of [name], driven by completions rather than a waiting thread: contends until a term is won or
     * [timeout] passes on the client's clock (one answered attempt with [probe]). A term revoked right after it began
     * does not end the wait: the lock contends again within the timeout.
     *
     * [result] completes on the callback executor (on the event loop only with a direct one, as callbacks do), and its
     * dependents run there like callbacks: `close()` in them does not wait. A term that ended before [result] could
     * complete with it is not returned: the lock contends again. Completing [result] other than with the term this
     * attempt won (cancelling it) abandons the attempt: the current run stops and a term already won is released.
     * The deadline timer is cancelled once [result] completes.
     */
    private inner class LockAttempt(
        private val name: LeaseName,
        private val timeout: Duration,
        private val probe: Boolean,
    ) {
        val result = CompletableFuture<LockTerm?>()
        private val deadline = CompletableFuture<Void>()

        // Monotonic instants are compared by difference, so a saturated deadline that wraps still lies ahead.
        private val timer = shell.schedule(ticker.nanoTime() + saturatedNanos(timeout)) { deadline.complete(null) }
        private val lock = Any()
        private var current: Run? = null
        private var won: LockTerm? = null

        init {
            result.whenComplete { term, _ ->
                timer.cancel()
                // Ends every pending wait of this attempt, so none stays registered on the client's close future.
                deadline.complete(null)
                val abandoned = synchronized(lock) { current.takeIf { term == null || term !== won } }
                abandoned?.let { stopRun(name, it) }
            }
            contend()
        }

        /** Starts a run once no other run of this client holds the name; waits for a local holder unless probing. */
        private fun contend() {
            while (!result.isDone) {
                val run = Run()
                val listener = LockListener(name, run)
                val lost = if (probe) CompletableFuture<Void>() else null
                val started = try {
                    startRun(name, run, listener, ElectionKind.LOCK, probe = lost)
                } catch (error: IllegalStateException) {
                    finish { result.completeExceptionally(error) }
                    return
                }
                if (started) {
                    synchronized(lock) { current = run }
                    if (result.isDone) {
                        stopRun(name, run)
                    } else {
                        anyOf(listener.elected, lost, deadline, closed).whenComplete { _, _ -> settle(run, listener) }
                    }
                    return
                }
                if (probe) {
                    finish { result.complete(null) }
                    return
                }
                val holder = running[name] ?: continue
                anyOf(holder.stopped, deadline, closed).whenComplete { _, _ -> afterLocalWait() }
                return
            }
        }

        private fun afterLocalWait() {
            if (result.isDone) {
                return
            }
            if (deadline.isDone && !closing.get()) {
                timedOut()
            } else {
                contend()
            }
        }

        private fun settle(run: Run, listener: LockListener) {
            if (result.isDone) {
                // Abandoned: the completion of the result already stopped the run.
                return
            }
            val term = listener.elected.getNow(null)
            if (term != null && term.isValid) {
                val held = ShellLockTerm(term) { stopRun(name, run) }
                synchronized(lock) { won = held }
                finish {
                    when {
                        // Revoked while the completion waited for the callback executor: contend again.
                        !held.isValid -> lost(run)
                        !result.complete(held) -> held.releaseAsync()
                    }
                }
                return
            }
            lost(run)
        }

        /** [run] ended without a term to return: stop it, then contend again unless the attempt is over. */
        private fun lost(run: Run) {
            stopRun(name, run)
            when {
                closing.get() -> contend()
                probe -> finish { result.complete(null) }
                deadline.isDone -> timedOut()
                else -> contend()
            }
        }

        /** Only a waiting acquire times out; a probe ends with its one attempt. */
        private fun timedOut() {
            finish { result.completeExceptionally(LockTimeoutException(name, timeout)) }
        }

        /**
         * Completes [result] on the callback executor, marked as a callback, so that its dependents run where callbacks
         * run and a blocking `close()` in them does not wait for the executor they occupy.
         */
        private fun finish(complete: () -> Unit) {
            completeAsCallback("LockCompletion($name)", complete)
        }

        private fun anyOf(vararg futures: CompletableFuture<*>?): CompletableFuture<Any> =
            CompletableFuture.anyOf(*futures.filterNotNull().toTypedArray())
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

    /**
     * Runs [complete] on the callback executor marked as a callback (on the caller when the executor rejects it), so
     * that dependents of what it completes run where callbacks run, never on the event loop unless callbacks do, and a
     * blocking `close()` in them does not wait for the executor they occupy.
     */
    private fun completeAsCallback(label: String, complete: () -> Unit) {
        val completion = object : Runnable {
            override fun run() {
                EngineShell.runAsCallback(complete)
            }

            override fun toString(): String = label
        }
        try {
            callbackExecutor.execute(completion)
        } catch (_: RejectedExecutionException) {
            completion.run()
        }
    }

    private inner class ShellLockTerm(
        private val term: Term,
        private val release: () -> CompletableFuture<Void>,
    ) : LockTerm, Term by term {
        /** The release, completed like a lock result: on the callback executor, never directly on the loop. */
        private val released by lazy {
            val relayed = CompletableFuture<Void>()
            release().whenComplete { _, error ->
                completeAsCallback("LockRelease(${term.name})") {
                    if (error == null) relayed.complete(null) else relayed.completeExceptionally(error)
                }
            }
            relayed
        }

        override fun releaseAsync(): CompletionStage<Void> = released.loopGuarded()

        override fun close() {
            join(releaseAsync())
        }

        override fun toString(): String = "LockTerm($term)"
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

        /** Stops of partitions this node no longer owns, still handing over. */
        private val handingOver = HashSet<CompletableFuture<Void>>()
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
                (tasks.keys - mine).forEach { partition ->
                    tasks.remove(partition)?.let(::stopNow)?.let { handover ->
                        handingOver += handover
                        handover.whenComplete { _, _ -> synchronized(lock) { handingOver -= handover } }
                    }
                }
                (mine - tasks.keys).forEach { partition ->
                    val name = LeaseName(partitionName(group.name, partition))
                    try {
                        tasks[partition] = startPartition(ShellElection(name, ElectionKind.GROUP), partition)
                    } catch (error: IllegalStateException) {
                        log.warn(error) { "Partition [$name] of group [${group.name}] could not start." }
                    }
                }
            }
        }

        override fun partitions(): Set<Int> = synchronized(lock) { tasks.keys.toSet() }

        override fun stop(): CompletionStage<Void> {
            val all = synchronized(lock) {
                stopped?.let { return it.loopGuarded() }
                val stops = tasks.values.map(::stopNow) + handingOver
                tasks.clear()
                CompletableFuture.allOf(*stops.toTypedArray()).also { stopped = it }
            }
            shell.leave(group.name)
            all.whenComplete { _, _ -> groups.remove(group.name) }
            return all.loopGuarded()
        }

        override fun close() {
            join(stop())
        }
    }

    private fun partitionName(group: String, partition: Int): String = "$group.$partition"

    /** Stops [task] and returns its stop as a plain future, never a [LoopGuardedStage]. */
    private fun stopNow(task: LeaderTask): CompletableFuture<Void> =
        (task as? ElectionTask)?.stopNow() ?: task.stop().toCompletableFuture()

    // endregion

    class Builder internal constructor() {
        internal var backend: LeaseBackend? = null
        internal var config: SimbaConfig = SimbaConfig.builder().build()
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

/**
 * Waits until one of [futures] completes. Only the client's close future can complete exceptionally; that ends the
 * wait as a closed client ([IllegalStateException]), never as a leaked [ExecutionException].
 */
@Throws(InterruptedException::class)
internal fun awaitAny(vararg futures: CompletableFuture<*>?) {
    try {
        CompletableFuture.anyOf(*futures.filterNotNull().toTypedArray()).get()
    } catch (error: ExecutionException) {
        throw IllegalStateException("Simba is closed.", error.cause ?: error)
    }
}

/**
 * The stage of [this] future for callers: a [LoopGuardedStage], so that waiting for it on the event loop fails fast
 * instead of blocking the loop it waits for.
 */
internal fun <T> CompletableFuture<T>.loopGuarded(): CompletionStage<T> = LoopGuardedStage(this)

/**
 * A read-only stage of [future]. On an event loop thread (a callback or listener run by a direct callback executor)
 * [toCompletableFuture] throws [IllegalStateException] while the future is pending: the usual `join()` on it would
 * block the loop that has to complete it. Chain on the stage there instead.
 */
internal class LoopGuardedStage<T>(private val future: CompletableFuture<T>) :
    CompletionStage<T> by future.minimalCompletionStage() {
    val isDone: Boolean get() = future.isDone

    override fun toCompletableFuture(): CompletableFuture<T> {
        check(future.isDone || !EngineShell.isLoopThread()) {
            "Waiting for this stage on the Simba event loop would block the loop that completes it; chain on it instead."
        }
        return future.minimalCompletionStage().toCompletableFuture()
    }

    override fun toString(): String = "LoopGuardedStage($future)"
}
