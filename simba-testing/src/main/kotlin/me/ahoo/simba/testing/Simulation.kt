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

import me.ahoo.simba.RevokeReason
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.engine.BackendRequest
import me.ahoo.simba.engine.BackendResult
import me.ahoo.simba.engine.Effect
import me.ahoo.simba.engine.EngineConfig
import me.ahoo.simba.engine.EngineState
import me.ahoo.simba.engine.EngineTerm
import me.ahoo.simba.engine.Event
import me.ahoo.simba.engine.TermRegistry
import me.ahoo.simba.engine.execute
import me.ahoo.simba.engine.step
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import java.time.Duration
import java.util.Objects
import java.util.PriorityQueue
import java.util.SplittableRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

/**
 * Knobs of a [Simulation], built with [builder]. Durations are virtual. A plain class with value equality and no
 * positional constructor, so knobs can be added compatibly.
 *
 * @property maxDelay each run draws its fault-phase delay ceiling from `[1ms, maxDelay]`; above half the renew interval,
 *   replies arrive after the call timed out.
 * @property maxDropRate each run draws its fault-phase loss rate from `[0, maxDropRate]`.
 * @property quietMaxDelay delay ceiling of the quiet phase.
 * @property clockRateSpread node clock rates are drawn from `[1 − spread, 1 + spread]`; the engine is only safe while
 *   the spread does not exceed [SimbaConfig.drift], which is what the checker's self-test relies on.
 * @property workShare share of starts whose terms carry work; such work exits on its own with probability
 *   [selfExitShare] within [maxWork], and otherwise within [maxWork] after its term is revoked (often after the
 *   handover timed out).
 * @property selfExpiringLeases runs [InMemoryLeaseBackend] with leases that expire on their own, as on Redis.
 * @property sessionBoundGrants with [selfExpiringLeases], a grant lasts only as long as its session has left, as on a
 *   single Redis node, instead of one full lease.
 */
class SimulationParams private constructor(builder: Builder) {
    /** The defaults; see [builder] to change knobs. */
    constructor() : this(Builder())

    val config: SimbaConfig = builder.config
    val minNodes: Int = builder.minNodes
    val maxNodes: Int = builder.maxNodes
    val minNames: Int = builder.minNames
    val maxNames: Int = builder.maxNames
    val maxDelay: Duration = builder.maxDelay
    val maxDropRate: Double = builder.maxDropRate
    val quietMaxDelay: Duration = builder.quietMaxDelay
    val clockRateSpread: Double = builder.clockRateSpread ?: config.drift
    val meanPauseInterval: Duration = builder.meanPauseInterval
    val maxPause: Duration = builder.maxPause ?: config.leaseDuration.multipliedBy(2)
    val meanOperationInterval: Duration = builder.meanOperationInterval
    val faultPhase: Duration = builder.faultPhase
    val quietPhase: Duration = builder.quietPhase
    val workShare: Double = builder.workShare
    val selfExitShare: Double = builder.selfExitShare
    val maxWork: Duration = builder.maxWork ?: config.leaseDuration.multipliedBy(2)
    val selfExpiringLeases: Boolean = builder.selfExpiringLeases
    val sessionBoundGrants: Boolean = builder.sessionBoundGrants

    /**
     * I4 bound, measured from the start of the quiet phase: fault-phase messages land within two delays, a cooldown
     * or a stale session may block the lease for one lease duration each, then a candidate retries within one renew
     * interval and a round trip.
     */
    val livenessBound: Duration =
        maxDelay.multipliedBy(2) + config.leaseDuration.multipliedBy(2) + config.renewInterval.multipliedBy(2) +
            quietMaxDelay.multipliedBy(4)

    /** A builder holding every knob of these params, derived ones included. */
    fun toBuilder(): Builder =
        Builder()
            .config(config)
            .minNodes(minNodes)
            .maxNodes(maxNodes)
            .minNames(minNames)
            .maxNames(maxNames)
            .maxDelay(maxDelay)
            .maxDropRate(maxDropRate)
            .quietMaxDelay(quietMaxDelay)
            .clockRateSpread(clockRateSpread)
            .meanPauseInterval(meanPauseInterval)
            .maxPause(maxPause)
            .meanOperationInterval(meanOperationInterval)
            .faultPhase(faultPhase)
            .quietPhase(quietPhase)
            .workShare(workShare)
            .selfExitShare(selfExitShare)
            .maxWork(maxWork)
            .selfExpiringLeases(selfExpiringLeases)
            .sessionBoundGrants(sessionBoundGrants)

    private fun values(): List<Any> = listOf(config, minNodes, maxNodes, minNames, maxNames, maxDelay, maxDropRate, quietMaxDelay, clockRateSpread, meanPauseInterval, maxPause, meanOperationInterval, faultPhase, quietPhase, workShare, selfExitShare, maxWork, selfExpiringLeases, sessionBoundGrants)

    override fun equals(other: Any?): Boolean = this === other || other is SimulationParams && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String =
        "SimulationParams(" +
            "config=$config, minNodes=$minNodes, maxNodes=$maxNodes, minNames=$minNames, " +
            "maxNames=$maxNames, maxDelay=$maxDelay, maxDropRate=$maxDropRate, quietMaxDelay=$quietMaxDelay, " +
            "clockRateSpread=$clockRateSpread, meanPauseInterval=$meanPauseInterval, maxPause=$maxPause, meanOperationInterval=$meanOperationInterval, " +
            "faultPhase=$faultPhase, quietPhase=$quietPhase, workShare=$workShare, selfExitShare=$selfExitShare, " +
            "maxWork=$maxWork, selfExpiringLeases=$selfExpiringLeases, sessionBoundGrants=$sessionBoundGrants)"

    /** Builds [SimulationParams]; `clockRateSpread` defaults to the config's drift, `maxPause` and `maxWork` to two leases. */
    class Builder internal constructor() {
        internal var config: SimbaConfig = SimbaConfig.builder().leaseDuration(
            Duration.ofSeconds(3)
        ).renewInterval(
            Duration.ofSeconds(1)
        ).drift(
            0.01
        ).margin(Duration.ofMillis(20)).retryJitter(Duration.ofMillis(500)).minBackoff(Duration.ofMillis(50)).build()
            private set
        internal var minNodes: Int = 2
            private set
        internal var maxNodes: Int = 5
            private set
        internal var minNames: Int = 1
            private set
        internal var maxNames: Int = 3
            private set
        internal var maxDelay: Duration = Duration.ofMillis(700)
            private set
        internal var maxDropRate: Double = 0.3
            private set
        internal var quietMaxDelay: Duration = Duration.ofMillis(50)
            private set
        internal var clockRateSpread: Double? = null
            private set
        internal var meanPauseInterval: Duration = Duration.ofSeconds(8)
            private set
        internal var maxPause: Duration? = null
            private set
        internal var meanOperationInterval: Duration = Duration.ofSeconds(2)
            private set
        internal var faultPhase: Duration = Duration.ofSeconds(60)
            private set
        internal var quietPhase: Duration = Duration.ofSeconds(30)
            private set
        internal var workShare: Double = 0.5
            private set
        internal var selfExitShare: Double = 0.3
            private set
        internal var maxWork: Duration? = null
            private set
        internal var selfExpiringLeases: Boolean = false
            private set
        internal var sessionBoundGrants: Boolean = false
            private set

        fun config(config: SimbaConfig) = apply { this.config = config }

        fun minNodes(minNodes: Int) = apply { this.minNodes = minNodes }

        fun maxNodes(maxNodes: Int) = apply { this.maxNodes = maxNodes }

        fun minNames(minNames: Int) = apply { this.minNames = minNames }

        fun maxNames(maxNames: Int) = apply { this.maxNames = maxNames }

        fun maxDelay(maxDelay: Duration) = apply { this.maxDelay = maxDelay }

        fun maxDropRate(maxDropRate: Double) = apply { this.maxDropRate = maxDropRate }

        fun quietMaxDelay(quietMaxDelay: Duration) = apply { this.quietMaxDelay = quietMaxDelay }

        fun clockRateSpread(clockRateSpread: Double) = apply { this.clockRateSpread = clockRateSpread }

        fun meanPauseInterval(meanPauseInterval: Duration) = apply { this.meanPauseInterval = meanPauseInterval }

        fun maxPause(maxPause: Duration) = apply { this.maxPause = maxPause }

        fun meanOperationInterval(meanOperationInterval: Duration) = apply {
            this.meanOperationInterval = meanOperationInterval
        }

        fun faultPhase(faultPhase: Duration) = apply { this.faultPhase = faultPhase }

        fun quietPhase(quietPhase: Duration) = apply { this.quietPhase = quietPhase }

        fun workShare(workShare: Double) = apply { this.workShare = workShare }

        fun selfExitShare(selfExitShare: Double) = apply { this.selfExitShare = selfExitShare }

        fun maxWork(maxWork: Duration) = apply { this.maxWork = maxWork }

        fun selfExpiringLeases(selfExpiringLeases: Boolean) = apply { this.selfExpiringLeases = selfExpiringLeases }

        fun sessionBoundGrants(sessionBoundGrants: Boolean) = apply { this.sessionBoundGrants = sessionBoundGrants }

        fun build(): SimulationParams = SimulationParams(this)
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/** What a run did, for sanity checks and replay comparisons. */
class SimulationResult(
    val seed: Long,
    val nodes: Int,
    val names: Int,
    val tasks: Long,
    val elections: Int,
    val handovers: Int,
    val handoverTimeouts: Int,
    val revocations: Map<RevokeReason, Int>,
    val highestTokens: Map<LeaseName, Long>,
    val sessions: Int,
    val events: Map<String, Int>,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SimulationResult &&
            seed == other.seed &&
            nodes == other.nodes &&
            names == other.names &&
            tasks == other.tasks &&
            elections == other.elections &&
            handovers == other.handovers &&
            handoverTimeouts == other.handoverTimeouts &&
            revocations == other.revocations &&
            highestTokens == other.highestTokens &&
            sessions == other.sessions &&
            events == other.events

    override fun hashCode(): Int = Objects.hash(seed, nodes, names, tasks, elections, handovers, handoverTimeouts, revocations, highestTokens, sessions, events)

    override fun toString(): String = "SimulationResult(seed=$seed, nodes=$nodes, names=$names, tasks=$tasks, elections=$elections, handovers=$handovers, handoverTimeouts=$handoverTimeouts, revocations=$revocations, highestTokens=$highestTokens, sessions=$sessions, events=$events)"
}

/** An invariant was violated; [seed] replays the run exactly. */
class SimulationFailure(val seed: Long, message: String, trace: String) :
    AssertionError("seed=$seed: $message\nReplay: -Psimba.simulation.seed=$seed\nLast events:\n$trace")

/**
 * Deterministic simulation (ADR 0010 §12): several engines driven through the pure [step] function against one
 * [InMemoryLeaseBackend], in virtual time, with random message delays and losses, process pauses, clock drift and
 * random start/stop/step-down operations. A fault phase is followed by a quiet phase (no losses, pauses or
 * operations), then every election stops and every node closes.
 *
 * Checked continuously:
 * - **I1 safety**: at any instant at most one valid term per name; a valid term is also the backend's owner;
 * - **I2 monotonic**: tokens of a name strictly increase in the order terms are created;
 * - **I3 pairing**: per node and name, `onElected` and `onRevoked` alternate for the same term; no callback after a
 *   stop completed (until the next start) or after close; at the end no term is open;
 * - **I4 liveness**: during the quiet phase, after [SimulationParams.livenessBound], every name has a valid term;
 * - **I5 handover** (ADR 0010 contract 3): a term stopped or stepped down while its work runs keeps its lease (no
 *   release, no session close) until the work exited or the session's local deadline at the time of the stop
 *   passed.
 *
 * Everything random comes from [seed], so a failing seed replays exactly.
 */
class Simulation(val seed: Long, private val params: SimulationParams = SimulationParams()) {
    private val random = SplittableRandom(seed)
    private val clock = VirtualClock()
    private var sessionCounter = 0
    private val backend = InMemoryLeaseBackend(clock, params.selfExpiringLeases, params.sessionBoundGrants) {
        SessionId("s${++sessionCounter}")
    }
    private val engineConfig = EngineConfig.of(params.config)
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L
    private var tasks = 0L
    private val trace = ArrayDeque<() -> String>()

    private val faultEnd = params.faultPhase.toNanos()
    private val quietEnd = faultEnd + params.quietPhase.toNanos()
    private val livenessFrom = faultEnd + params.livenessBound.toNanos()
    private var faulty = true
    private val dropRate = random.nextDouble() * params.maxDropRate
    private val maxDelay = random.nextLong(MILLIS, params.maxDelay.toNanos() + 1)
    private val events = HashMap<String, Int>()

    private val names = List(random.nextInt(params.minNames, params.maxNames + 1)) { LeaseName("lease-$it") }
    private val nodes = List(random.nextInt(params.minNodes, params.maxNodes + 1)) { Node(it) }

    private val lastToken = HashMap<LeaseName, Long>()
    private var elections = 0
    private var handovers = 0
    private var handoverTimeouts = 0
    private val revocations = HashMap<RevokeReason, Int>()

    private class Task(val at: Long, val seq: Long, val action: () -> Unit)

    /** Work bound to [term]; [handoverUntil] is set (local time) when a stop or step-down revoked it while running. */
    private class Work(val term: EngineTerm) {
        var exited = false
        var handoverUntil: Long? = null
        var timedOut = false
    }

    private inner class Node(val index: Int) {
        val ticker = DriftingTicker(
            clock,
            offset = random.nextLong(0, OFFSET_RANGE),
            rate = 1.0 + (random.nextDouble() * 2 - 1) * params.clockRateSpread,
        )
        var state = EngineState.initial(engineConfig, random.nextLong())
        val terms = TermRegistry(ticker)
        var pausedUntil = Long.MIN_VALUE
        val buffered = ArrayDeque<Event>()
        val unresolved = HashSet<Long>()
        val started = HashSet<LeaseName>()
        val stopFutures = HashSet<CompletableFuture<Void>>()
        val starts = HashMap<LeaseName, Int>()
        val stopEpochs = HashMap<CompletableFuture<Void>, Int>()
        val stopped = HashSet<LeaseName>()
        val openTerms = HashMap<LeaseName, EngineTerm>()
        val waiting = ArrayList<CompletableFuture<Void>>()
        val workNames = HashSet<LeaseName>()
        val works = HashMap<Long, Work>()
        var closeRequested = false
        var closed = false

        val paused: Boolean get() = clock.nanoTime() < pausedUntil

        override fun toString(): String = "n$index"
    }

    fun run(): SimulationResult {
        backend.subscribe { hint ->
            nodes.forEach { node -> send { deliver(node, Event.Hint(hint)) } }
        }
        for (node in nodes) {
            schedule(random.nextLong(0, MILLIS * 50)) { deliver(node, Event.Boot) }
            for (name in names) {
                schedule(random.nextLong(0, MILLIS * 200)) { start(node, name) }
            }
        }
        schedulePoisson(params.meanPauseInterval) { pauseSomeone() }
        schedulePoisson(params.meanOperationInterval) { operateSomeone() }
        schedule(faultEnd) { quiet() }
        schedule(quietEnd) { shutdown() }
        drain()
        finish()
        return SimulationResult(
            seed = seed,
            nodes = nodes.size,
            names = names.size,
            tasks = tasks,
            elections = elections,
            handovers = handovers,
            handoverTimeouts = handoverTimeouts,
            revocations = revocations.toMap(),
            highestTokens = names.associateWith { backend.token(it) },
            sessions = sessionCounter,
            events = events.toMap(),
        )
    }

    // region scheduling

    private fun schedule(at: Long, action: () -> Unit) {
        queue += Task(maxOf(at, clock.nanoTime()), seq++, action)
    }

    private fun after(delay: Long, action: () -> Unit) = schedule(clock.nanoTime() + delay, action)

    /** Delivers [action] after a random network delay, unless the message is dropped. */
    private fun send(action: () -> Unit) {
        if (faulty && random.nextDouble() < dropRate) {
            return
        }
        val ceiling = if (faulty) maxDelay else params.quietMaxDelay.toNanos()
        after(random.nextLong(0, ceiling + 1), action)
    }

    private fun schedulePoisson(mean: Duration, action: () -> Unit) {
        val next = clock.nanoTime() + (-Math.log(1 - random.nextDouble()) * mean.toNanos()).toLong()
        if (next >= faultEnd) {
            return
        }
        schedule(next) {
            action()
            schedulePoisson(mean, action)
        }
    }

    private fun drain() {
        val limit = quietEnd + params.config.leaseDuration.toNanos() * DRAIN_LEASES
        while (queue.isNotEmpty()) {
            val task = queue.poll()
            if (task.at > limit) {
                val grace = Duration.ofNanos(limit - quietEnd)
                fail("events still pending $grace after shutdown: the engine never quiesces")
            }
            clock.advanceTo(task.at)
            tasks++
            task.action()
            checkSafety()
            checkLiveness()
        }
    }

    // endregion

    // region nodes

    private fun deliver(node: Node, event: Event) {
        if (node.paused || node.buffered.isNotEmpty()) {
            node.buffered += event
        } else {
            process(node, event)
        }
    }

    private fun process(node: Node, event: Event) {
        when (event) {
            is Event.Start -> {
                node.stopped -= event.name
                node.starts.merge(event.name, 1, Int::plus)
                if (event.work) node.workNames += event.name else node.workNames -= event.name
            }

            is Event.Stop -> node.stopEpochs[event.done] = node.starts[event.name] ?: 0
            else -> Unit
        }
        val (next, effects) = step(node.state, event, node.ticker.nanoTime())
        node.state = next
        val at = clock.nanoTime()
        record { "${millis(at)} $node <- $event => $effects" }
        effects.forEach { apply(node, it) }
    }

    private fun apply(node: Node, effect: Effect) {
        when (effect) {
            is Effect.Call -> call(node, effect)
            is Effect.Arm -> schedule(node.ticker.baseTimeOf(effect.at)) { deliver(node, Event.Timer(effect.id)) }
            is Effect.Deadline -> node.terms.deadline(effect)
            is Effect.Elected -> elected(node, node.terms.elected(effect))
            is Effect.Revoked -> {
                val remaining = node.terms.active.first { it.id == effect.term }.remaining().toNanos()
                revoked(node, node.terms.revoked(effect), effect.reason, remaining)
            }

            is Effect.Complete -> {
                if (effect.done in node.stopFutures) {
                    node.stopFutures -= effect.done
                    // A handover may complete a stop after the election was started again: only a stop with no
                    // later start leaves the election stopped.
                    val epoch = node.stopEpochs.remove(effect.done)
                    effect.name?.takeIf { epoch == (node.starts[it] ?: 0) }?.let { node.stopped += it }
                }
                if (node.closeRequested && effect.name == null) {
                    node.closed = true
                }
                effect.done.complete(null)
            }

            is Effect.Publish -> events.merge(effect.event.javaClass.simpleName, 1, Int::plus)
            is Effect.Members -> Unit
        }
    }

    private fun call(node: Node, effect: Effect.Call) {
        checkHandover(node, effect.request)
        node.unresolved += effect.requestId
        after(effect.timeout) {
            if (node.unresolved.remove(effect.requestId)) {
                deliver(node, Event.Reply(effect.requestId, BackendResult.Failed(TimeoutException("timeout"))))
            }
        }
        send {
            val result = backend.execute(effect.request)
            val at = clock.nanoTime()
            record { "${millis(at)} backend ${effect.request} -> $result" }
            send {
                if (node.unresolved.remove(effect.requestId)) {
                    deliver(node, Event.Reply(effect.requestId, result))
                }
            }
        }
    }

    private fun start(node: Node, name: LeaseName) {
        if (node.started.add(name)) {
            deliver(node, Event.Start(name, work = random.nextDouble() < params.workShare))
        }
    }

    /** Starts the work of [term] when its election carries work; some work exits on its own. */
    private fun startWork(node: Node, term: EngineTerm) {
        if (term.name !in node.workNames) {
            return
        }
        val work = Work(term)
        node.works[term.id] = work
        if (random.nextDouble() < params.selfExitShare) {
            after(random.nextLong(0, params.maxWork.toNanos())) { exitWork(node, work) }
        }
    }

    private fun exitWork(node: Node, work: Work) {
        if (work.exited) {
            return
        }
        work.exited = true
        node.works -= work.term.id
        record { "${millis(clock.nanoTime())} $node work of ${work.term} exited" }
        deliver(node, Event.WorkExited(work.term.name, work.term.id))
    }

    private fun pauseSomeone() {
        val node = nodes[random.nextInt(nodes.size)]
        if (node.paused) {
            return
        }
        val until = minOf(clock.nanoTime() + random.nextLong(1, params.maxPause.toNanos()), faultEnd)
        node.pausedUntil = until
        record { "${millis(clock.nanoTime())} $node paused until ${millis(until)}" }
        schedule(until) { resume(node) }
    }

    private fun resume(node: Node) {
        while (node.buffered.isNotEmpty() && !node.paused) {
            process(node, node.buffered.removeFirst())
        }
    }

    private fun operateSomeone() {
        val node = nodes[random.nextInt(nodes.size)]
        val name = names[random.nextInt(names.size)]
        if (name !in node.started) {
            start(node, name)
            return
        }
        val roll = random.nextDouble()
        when {
            roll < STEP_DOWN_SHARE -> {
                val cooldown = random.nextLong(0, params.config.leaseDuration.toNanos())
                deliver(node, Event.StepDown(name, cooldown, CompletableFuture()))
            }

            roll < STEP_DOWN_SHARE + STOP_SHARE -> stop(node, name)
            else -> Unit
        }
    }

    private fun stop(node: Node, name: LeaseName) {
        node.started -= name
        val done = CompletableFuture<Void>()
        node.stopFutures += done
        node.waiting += done
        deliver(node, Event.Stop(name, done))
    }

    private fun quiet() {
        faulty = false
        record { "${millis(clock.nanoTime())} quiet phase" }
        for (node in nodes) {
            names.forEach { start(node, it) }
        }
    }

    private fun shutdown() {
        for (node in nodes) {
            node.started.toList().forEach { stop(node, it) }
            val done = CompletableFuture<Void>()
            node.waiting += done
            after(engineConfig.renewInterval) {
                node.closeRequested = true
                deliver(node, Event.Close(done))
            }
        }
    }

    // endregion

    // region invariants

    private fun elected(node: Node, term: EngineTerm) {
        elections++
        if (node.closed) {
            fail("I3: $node elected $term after close")
        }
        if (term.name in node.stopped) {
            fail("I3: $node elected $term after its stop completed")
        }
        node.openTerms[term.name]?.let { fail("I3: $node elected $term while $it is still open") }
        node.openTerms[term.name] = term
        startWork(node, term)
        if (term.token <= 0) {
            fail("I2: $node elected non-positive token $term")
        }
        val last = lastToken[term.name] ?: 0
        if (term.token <= last) {
            fail("I2: $node elected $term after token $last was already granted")
        }
        lastToken[term.name] = term.token
    }

    private fun revoked(node: Node, term: EngineTerm, reason: RevokeReason, remaining: Long) {
        revocations.merge(reason, 1, Int::plus)
        node.works[term.id]?.let { work ->
            if (reason == RevokeReason.STOPPED || reason == RevokeReason.STEPPED_DOWN) {
                handovers++
                work.handoverUntil = node.ticker.nanoTime() + remaining
            }
            after(random.nextLong(0, params.maxWork.toNanos())) { exitWork(node, work) }
        }
        if (node.closed) {
            fail("I3: $node revoked $term after close")
        }
        val open = node.openTerms.remove(term.name)
        if (open !== term) {
            fail("I3: $node revoked $term but the open term was $open")
        }
        if (term.isValid || !term.revoked.toCompletableFuture().isDone) {
            fail("I3: $term still valid after revocation")
        }
    }

    private fun checkSafety() {
        for (name in names) {
            var valid: EngineTerm? = null
            var holder: Node? = null
            for (node in nodes) {
                val term = node.terms.active.firstOrNull { it.name == name && it.isValid } ?: continue
                if (valid != null) {
                    fail("I1: $holder holds $valid and $node holds $term, both valid")
                }
                valid = term
                holder = node
                val owner = backend.owner(name)
                if (owner == null || owner.session != term.session || owner.token != term.token) {
                    fail("I1: $node holds valid $term of session ${term.session} but the backend owner is $owner")
                }
            }
        }
    }

    /** I5: a release or session close must not free the lease of a handover whose work runs and has time left. */
    private fun checkHandover(node: Node, request: BackendRequest) {
        val now = node.ticker.nanoTime()
        for (work in node.works.values) {
            val until = work.handoverUntil ?: continue
            val term = work.term
            val frees = when (request) {
                is BackendRequest.Release -> request.session == term.session && request.leases[term.name] == term.token
                is BackendRequest.CloseSession -> request.session == term.session
                else -> false
            }
            if (frees && now - until >= 0) {
                if (!work.timedOut) {
                    work.timedOut = true
                    handoverTimeouts++
                }
            } else if (frees) {
                val left = Duration.ofNanos(until - now)
                fail("I5: $node sent $request while the work of $term still runs, $left before its handover timeout")
            }
        }
    }

    private fun checkLiveness() {
        val now = clock.nanoTime()
        if (now < livenessFrom || now >= quietEnd) {
            return
        }
        for (name in names) {
            if (nodes.none { node -> node.terms.active.any { it.name == name && it.isValid } }) {
                val into = millis(now - faultEnd)
                fail("I4: no valid term of $name $into into the quiet phase (bound ${params.livenessBound})")
            }
        }
    }

    private fun finish() {
        for (node in nodes) {
            node.waiting.firstOrNull { !it.isDone }?.let { fail("I3: $node stop/close never completed") }
            if (!node.closed) {
                fail("I3: $node never closed")
            }
            if (node.openTerms.isNotEmpty() || node.terms.active.isNotEmpty()) {
                fail("I3: $node ended with open terms ${node.openTerms.values}")
            }
        }
    }

    private fun fail(message: String): Nothing =
        throw SimulationFailure(seed, message, trace.joinToString("\n") { it() })

    private fun record(line: () -> String) {
        if (trace.size == TRACE_SIZE) {
            trace.removeFirst()
        }
        trace.addLast(line)
    }

    // endregion

    private companion object {
        const val MILLIS = 1_000_000L
        const val OFFSET_RANGE = 1_000_000_000_000L
        const val STEP_DOWN_SHARE = 0.4
        const val STOP_SHARE = 0.3
        const val TRACE_SIZE = 400
        const val DRAIN_LEASES = 10

        fun millis(nanos: Long): String = "%.3fs".format(nanos / 1e9)
    }
}
