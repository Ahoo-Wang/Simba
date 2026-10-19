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

package me.ahoo.simba.engine

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.LeadershipListener
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.ElectionSnapshot
import me.ahoo.simba.event.ElectionStatus
import me.ahoo.simba.event.SessionSnapshot
import me.ahoo.simba.event.SessionStatus
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.event.SimbaSnapshot
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spi.Ticker
import me.ahoo.simba.util.SequentialExecutor
import me.ahoo.simba.util.Threads
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [step] on a single event-loop thread and executes its effects: backend calls on [ioExecutor] with a timeout
 * of one renew interval, timers on the loop (or on [scheduler]), callbacks on one serial dispatcher per election
 * over [callbackExecutor], and group views on the loop. The engine state is only touched by the loop thread.
 */
@OptIn(InternalSimbaApi::class)
internal class EngineShell(
    private val backend: LeaseBackend,
    private val config: EngineConfig,
    private val ticker: Ticker,
    private val ioExecutor: Executor,
    private val callbackExecutor: Executor,
    private val eventListeners: List<SimbaEventListener>,
    seed: Long,
    scheduler: Scheduler? = null,
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val dispatching = ThreadLocal<Boolean>()

        /** `true` on a thread currently running a leadership callback. */
        fun isDispatching(): Boolean = dispatching.get() == true
    }

    private val loop = ScheduledThreadPoolExecutor(1, Threads.defaultFactory("simba-engine", daemon = true)).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val timers: Scheduler = scheduler ?: LoopScheduler()
    private var state = EngineState.initial(config, seed)
    private val terms = TermRegistry(ticker)
    private val dispatchers = HashMap<LeaseName, Dispatcher>()
    private val groupViews = HashMap<String, (SessionId?, List<SessionId>) -> Unit>()
    private val handled = AtomicLong()
    private val hintSubscription = backend.subscribe { hint -> post(Event.Hint(hint)) }
    private var sessionStatus = SessionStatus.OPENING
    private var notLiveSince = ticker.nanoTime()

    /**
     * [active] while the election is started; [starts] counts starts. A stopped dispatcher is dropped once the
     * callbacks of its last stop ran, unless it was started again since.
     */
    private class Dispatcher(val executor: SequentialExecutor) {
        var listener: LeadershipListener? = null
        var active = false
        var starts = 0L
    }

    fun boot() {
        post(Event.Boot)
    }

    /** Timers on the loop with [ticker]. */
    private inner class LoopScheduler : Scheduler {
        override fun nanoTime(): Long = ticker.nanoTime()

        override fun schedule(at: Long, task: Runnable): Cancellable {
            val future = enqueue(task, at - ticker.nanoTime())
            if (future == null) {
                log.debug { "Simba engine is closed; timer runs at once." }
                task.run()
                return Cancellable.NONE
            }
            return Cancellable { future.cancel(false) }
        }
    }

    /**
     * Starts the election: with [work], its terms carry work that reports [workExited]; [probe] completes when the
     * first answered attempt does not elect this node.
     */
    fun start(
        name: LeaseName,
        listener: LeadershipListener,
        work: Boolean = false,
        probe: CompletableFuture<Void>? = null,
        kind: ElectionKind = ElectionKind.ELECTION,
    ) {
        onLoop {
            val dispatcher = dispatcher(name)
            if (dispatcher.active) {
                // Never swap the listener of a running election: its callbacks would reach the wrong listener.
                log.warn { "Election [$name] is already started; start ignored." }
                probe?.complete(null)
                return@onLoop
            }
            dispatcher.listener = listener
            dispatcher.active = true
            dispatcher.starts++
            handle(Event.Start(name, work, probe, kind))
        }
    }

    /** The work bound to term [term] of [name] exited; [event], if any, is published in the same loop task. */
    fun workExited(name: LeaseName, term: Long, event: SimbaEvent? = null) {
        onLoop {
            event?.let(::publishNow)
            handle(Event.WorkExited(name, term))
        }
    }

    /** Publishes [event] to the listeners from the loop, in order with the engine's own events. */
    fun publish(event: SimbaEvent) {
        onLoop { publishNow(event) }
    }

    /** Joins [group]; [onView] receives every changed view on the loop thread and must not block. */
    fun join(group: String, onView: (SessionId?, List<SessionId>) -> Unit) {
        onLoop {
            groupViews[group] = onView
            handle(Event.Join(group))
        }
    }

    fun leave(group: String) {
        onLoop {
            groupViews.remove(group)
            handle(Event.Leave(group))
        }
    }

    /** Runs [task] once the ticker reaches [at]; at once when the engine is closed. */
    fun schedule(at: Long, task: Runnable): Cancellable = timers.schedule(at, task)

    /**
     * Completes, with the number of loop tasks handled so far, once the loop has nothing queued. Meaningful only with
     * an external [Scheduler] (the loop then holds no delayed timers) and a direct I/O executor; tests use it to let
     * engines settle before moving virtual time: two rounds with the same counts mean nothing ran in between.
     */
    fun idle(): CompletableFuture<Long> {
        val idle = CompletableFuture<Long>()
        fun check() {
            if (loop.queue.isEmpty() || !submit(::check)) {
                idle.complete(handled.get())
            }
        }
        if (!submit(::check)) {
            idle.complete(handled.get())
        }
        return idle
    }

    /** Runs [action] on the loop without counting it as handled. */
    private fun submit(action: () -> Unit): Boolean = enqueue(action, 0) != null

    /**
     * Queues [task] on the loop after [delayNanos]; `null` when the loop no longer takes tasks. A task racing the
     * loop's termination is not rejected but silently cancelled by `ScheduledThreadPoolExecutor`, so a cancelled
     * future counts as not queued: callers then complete what they would otherwise wait for.
     */
    private fun enqueue(task: Runnable, delayNanos: Long): ScheduledFuture<*>? =
        try {
            loop.schedule(task, delayNanos, TimeUnit.NANOSECONDS).takeUnless { it.isCancelled }
        } catch (_: RejectedExecutionException) {
            null
        }

    /** Elections, dispatchers and groups kept by the engine; for tests of bounded state. */
    fun footprint(): CompletableFuture<Footprint> =
        CompletableFuture<Footprint>().also { footprint ->
            val posted = onLoop {
                footprint.complete(
                    Footprint(
                        elections = state.elections.size,
                        works = state.works.size,
                        pending = state.pending.size,
                        dispatchers = dispatchers.size,
                        groups = state.groups.size + groupViews.size,
                        terms = terms.active.size,
                        abandoned = (state.session as? SessionState.Active)?.abandoned?.size ?: 0,
                    )
                )
            }
            if (!posted) {
                footprint.complete(Footprint(0, 0, 0, 0, 0, 0, 0))
            }
        }

    /** The session and every election as of now; a closed snapshot once the loop stopped. */
    fun snapshot(): CompletableFuture<SimbaSnapshot> =
        CompletableFuture<SimbaSnapshot>().also { snapshot ->
            val posted = onLoop { snapshot.complete(snapshotNow()) }
            if (!posted) {
                val session = SessionSnapshot(
                    null,
                    SessionStatus.CLOSED,
                    Duration.ZERO,
                    Duration.ofNanos(ticker.nanoTime() - notLiveSince)
                )
                snapshot.complete(SimbaSnapshot(session, emptyList(), config.leaseDuration))
            }
        }

    private fun snapshotNow(): SimbaSnapshot {
        val now = ticker.nanoTime()
        val active = state.session as? SessionState.Active
        val session = SessionSnapshot(
            id = active?.id,
            status = sessionStatus,
            validFor = Duration.ofNanos((active?.validUntil?.minus(now) ?: 0).coerceAtLeast(0)),
            notLiveFor = if (sessionStatus == SessionStatus.LIVE) {
                Duration.ZERO
            } else {
                Duration.ofNanos(now - notLiveSince)
            },
        )
        val elections = state.elections.entries.sortedBy { it.key.value }.map { (name, election) ->
            val kind = state.kinds[name] ?: ElectionKind.ELECTION
            when (election) {
                is ElectionState.Candidate -> ElectionSnapshot(name, ElectionStatus.CANDIDATE, 0, Duration.ZERO, kind)
                is ElectionState.Cooldown -> ElectionSnapshot(name, ElectionStatus.COOLDOWN, 0, Duration.ZERO, kind)
                is ElectionState.Handover ->
                    ElectionSnapshot(name, ElectionStatus.HANDOVER, election.token, Duration.ZERO, kind)

                is ElectionState.Leader -> ElectionSnapshot(
                    name,
                    ElectionStatus.LEADER,
                    election.token,
                    terms.term(election.term)?.remaining() ?: Duration.ZERO,
                    kind,
                )
            }
        }
        return SimbaSnapshot(session, elections, config.leaseDuration)
    }

    private fun trackSessionStatus() {
        val status = when (val session = state.session) {
            is SessionState.Opening -> SessionStatus.OPENING
            is SessionState.Active -> if (session.suspended) SessionStatus.SUSPENDED else SessionStatus.LIVE
            is SessionState.Closed -> SessionStatus.CLOSED
        }
        if (sessionStatus == SessionStatus.LIVE && status != SessionStatus.LIVE) {
            notLiveSince = ticker.nanoTime()
        }
        sessionStatus = status
    }

    fun stop(name: LeaseName): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        val stopped = CompletableFuture<Void>()
        val accepted = onLoop {
            val dispatcher = dispatchers[name]
            dispatcher?.active = false
            val starts = dispatcher?.starts
            // `done` completes on the dispatcher after the stop's callbacks; then drop the dispatcher unless it was
            // started again since, and only then report the stop.
            done.whenComplete { _, _ ->
                val posted = onLoop {
                    val current = dispatchers[name]
                    if (current != null && !current.active && current.starts == starts) {
                        dispatchers.remove(name)
                    }
                    stopped.complete(null)
                }
                if (!posted) {
                    stopped.complete(null)
                }
            }
            handle(Event.Stop(name, done))
        }
        if (!accepted) {
            stopped.complete(null)
        }
        return stopped
    }

    /** Dispatchers kept, one per started election or stop still draining; for tests. */
    internal fun dispatcherCount(): CompletableFuture<Int> =
        CompletableFuture<Int>().also { count -> onLoop { count.complete(dispatchers.size) } }

    /** Steps down; completes at once when the loop is already shut down, like [stop]. */
    fun stepDown(name: LeaseName, cooldownNanos: Long): CompletableFuture<Void> =
        CompletableFuture<Void>().also { done ->
            if (!onLoop { handle(Event.StepDown(name, cooldownNanos, done)) }) {
                done.complete(null)
            }
        }

    /** Revokes every term, closes the session, waits for pending callbacks, then stops the loop. */
    fun close(): CompletableFuture<Void> {
        val closed = CompletableFuture<Void>()
        post(Event.Close(closed))
        return closed.thenCompose { drainDispatchers() }
            .whenComplete { _, _ ->
                hintSubscription.close()
                loop.shutdown()
            }
    }

    private fun drainDispatchers(): CompletableFuture<Void> {
        val drained = CompletableFuture<List<SequentialExecutor>>()
        onLoop { drained.complete(dispatchers.values.map { it.executor }) }
        return drained.thenCompose { executors ->
            val all = executors.map { executor ->
                val drainedOne = CompletableFuture<Void>()
                executor.execute { drainedOne.complete(null) }
                drainedOne
            }
            CompletableFuture.allOf(*all.toTypedArray())
        }
    }

    private fun post(event: Event) {
        onLoop { handle(event) }
    }

    /** Runs [action] on the loop; `false` when the loop is already shut down. */
    private fun onLoop(action: () -> Unit): Boolean {
        val queued = enqueue(
            {
                handled.incrementAndGet()
                try {
                    action()
                } catch (error: Throwable) {
                    log.error(error) { "Simba engine failed to handle an event." }
                }
            },
            0,
        )
        if (queued == null) {
            log.debug { "Simba engine is closed; event dropped." }
            return false
        }
        return true
    }

    private fun handle(event: Event) {
        val (next, effects) = step(state, event, ticker.nanoTime())
        state = next
        trackSessionStatus()
        effects.forEach(::execute)
    }

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.Call -> call(effect)
            is Effect.Arm -> timers.schedule(effect.at) { post(Event.Timer(effect.id)) }
            is Effect.Deadline -> terms.deadline(effect)
            is Effect.Elected -> {
                val term = terms.elected(effect)
                dispatch(effect.name) { it.onElected(term) }
            }

            is Effect.Revoked -> {
                val term = terms.revoked(effect)
                dispatch(effect.name) { it.onRevoked(term, effect.reason) }
            }

            is Effect.Complete -> {
                val dispatcher = effect.name?.let { dispatchers[it] }
                if (dispatcher == null) {
                    effect.done.complete(null)
                } else {
                    dispatcher.executor.execute { effect.done.complete(null) }
                }
            }

            is Effect.Publish -> publishNow(effect.event)
            is Effect.Members -> groupViews[effect.group]?.let { onView ->
                try {
                    onView(effect.session, effect.members)
                } catch (error: Throwable) {
                    log.warn(error) { "Group view of [${effect.group}] failed." }
                }
            }
        }
    }

    private fun publishNow(event: SimbaEvent) {
        eventListeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (error: Throwable) {
                log.warn(error) { "SimbaEventListener failed on $event." }
            }
        }
    }

    private fun call(effect: Effect.Call) {
        val replied = AtomicBoolean(false)
        var timeout = Cancellable.NONE
        fun reply(result: BackendResult) {
            if (replied.compareAndSet(false, true)) {
                timeout.cancel()
                post(Event.Reply(effect.requestId, result))
            }
        }
        timeout = timers.schedule(ticker.nanoTime() + config.renewInterval) {
            reply(BackendResult.Failed(TimeoutException("${effect.request} timed out.")))
        }
        if (replied.get()) {
            timeout.cancel()
        }
        try {
            ioExecutor.execute { reply(backend.execute(effect.request)) }
        } catch (error: RejectedExecutionException) {
            reply(BackendResult.Failed(error))
        }
    }

    private fun dispatcher(name: LeaseName): Dispatcher =
        dispatchers.getOrPut(name) { Dispatcher(SequentialExecutor(callbackExecutor)) }

    /** Captures the listener now, on the loop, so a restart with another listener never receives older callbacks. */
    private fun dispatch(name: LeaseName, callback: (LeadershipListener) -> Unit) {
        val dispatcher = dispatcher(name)
        val listener = dispatcher.listener ?: return
        dispatcher.executor.execute {
            dispatching.set(true)
            try {
                callback(listener)
            } catch (error: Throwable) {
                log.warn(error) { "Leadership callback of [$name] failed." }
            } finally {
                dispatching.remove()
            }
        }
    }
}

/** What an [EngineShell] keeps; every count returns to zero once nothing is started. */
internal data class Footprint(
    val elections: Int,
    val works: Int,
    val pending: Int,
    val dispatchers: Int,
    val groups: Int,
    val terms: Int,
    val abandoned: Int,
)
