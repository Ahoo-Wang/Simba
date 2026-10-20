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
import me.ahoo.simba.engine.ElectionState
import me.ahoo.simba.engine.EngineConfig
import me.ahoo.simba.engine.EngineState
import me.ahoo.simba.engine.EngineTerm
import me.ahoo.simba.engine.Event
import me.ahoo.simba.engine.SessionState
import me.ahoo.simba.engine.TermRegistry
import me.ahoo.simba.engine.execute
import me.ahoo.simba.engine.step
import me.ahoo.simba.partition.PartitionAssignment
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.Objects
import java.util.PriorityQueue
import java.util.SplittableRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.math.ceil

/**
 * Knobs of a [Simulation], built with [builder]. Durations are virtual. A plain class with value equality and no
 * positional constructor, so knobs can be added compatibly.
 *
 * @property maxDelay each run draws its fault-phase delay ceiling from `[1ms, maxDelay]`; above half the renew interval,
 *   replies arrive after the call timed out.
 * @property maxDropRate each run draws its fault-phase loss rate from `[0, maxDropRate]`.
 * @property quietMaxDelay delay ceiling of the quiet phase.
 * @property clockRateSpread node clock rates vary over time within `[1 − spread, 1 + spread]`. By default the spread
 *   exceeds [SimbaConfig.drift] by half of what [SimbaConfig.margin] covers over a lease, so the margin is needed,
 *   not just present; well beyond that the engine is unsafe, which is what the checker's self-test relies on.
 * @property workShare share of starts whose terms carry work; such work exits on its own with probability
 *   [selfExitShare] within [maxWork], and otherwise within [maxWork] after its term is revoked (often after the
 *   handover timed out).
 * @property selfExpiringLeases runs [InMemoryLeaseBackend] with leases that expire on their own, as on Redis.
 * @property sessionBoundGrants with [selfExpiringLeases], a grant lasts only as long as its session has left, as on
 *   Redis, instead of one full lease.
 * @property truncatedGrantShare share of runs whose backend reports less time than it grants (a factor drawn from
 *   `[0.3, 0.95]` per run), as a Zookeeper server capping the session timeout does; below two renew intervals of
 *   local validity the engine renews more often (`SessionLeaseShortened`).
 * @property meanInjectionInterval mean interval of backend-side faults during the fault phase: the backend expires a
 *   live session on its own, or deletes a held lease (which another node may then take over); `Duration.ZERO`
 *   disables them. Such faults break the backend's contract, so terms they hit are exempt from I1 and I2 (a grant in
 *   flight when its lease is deleted may be adopted after a later one) until revoked.
 * @property groupPartitions partitions of one group the nodes join and leave at random; each node runs the
 *   partitions `PartitionAssignment` gives it in its latest view. `0` disables groups.
 * @property backendHints the backend sends release and membership hints (InMemory, Redis, Zookeeper); without them
 *   the engine relies on its own retries and refreshes alone, as on JDBC.
 * @property earlyCloseShare share of random operations that close a node for good during the fault phase (never the
 *   last open node), with elections, work and pauses in any state.
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
    val clockRateSpread: Double = builder.clockRateSpread
        ?: (config.drift + config.margin.toNanos().toDouble() / config.leaseDuration.toNanos() / 2)
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
    val truncatedGrantShare: Double = builder.truncatedGrantShare
    val meanInjectionInterval: Duration = builder.meanInjectionInterval
    val groupPartitions: Int = builder.groupPartitions
    val earlyCloseShare: Double = builder.earlyCloseShare
    val backendHints: Boolean = builder.backendHints

    /**
     * I4 bound, measured from the start of the quiet phase, for names and partitions alike: fault-phase messages land
     * within two delays (a run uses its own delay ceiling, at most [maxDelay], in place of it), a stale session, a
     * cooldown or a handover holds a lease for at most one lease duration, a member view and a candidate retry take a
     * renew interval each, and a round trip a few quiet delays. Tight on purpose: an engine slower by one more lease
     * fails I4.
     */
    val livenessBound: Duration =
        maxDelay.multipliedBy(2) + config.leaseDuration + config.renewInterval.multipliedBy(3) +
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
            .truncatedGrantShare(truncatedGrantShare)
            .meanInjectionInterval(meanInjectionInterval)
            .groupPartitions(groupPartitions)
            .earlyCloseShare(earlyCloseShare)
            .backendHints(backendHints)

    private fun values(): List<Any> =
        listOf(
            config,
            minNodes,
            maxNodes,
            minNames,
            maxNames,
            maxDelay,
            maxDropRate,
            quietMaxDelay,
            clockRateSpread,
            meanPauseInterval,
            maxPause,
            meanOperationInterval,
            faultPhase,
            quietPhase,
            workShare,
            selfExitShare,
            maxWork,
            selfExpiringLeases,
            sessionBoundGrants,
            truncatedGrantShare,
            meanInjectionInterval,
            groupPartitions,
            earlyCloseShare,
            backendHints,
        )

    override fun equals(other: Any?): Boolean = this === other || other is SimulationParams && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String =
        "SimulationParams(" +
            "config=$config, minNodes=$minNodes, maxNodes=$maxNodes, minNames=$minNames, " +
            "maxNames=$maxNames, maxDelay=$maxDelay, maxDropRate=$maxDropRate, quietMaxDelay=$quietMaxDelay, " +
            "clockRateSpread=$clockRateSpread, meanPauseInterval=$meanPauseInterval, maxPause=$maxPause, meanOperationInterval=$meanOperationInterval, " +
            "faultPhase=$faultPhase, quietPhase=$quietPhase, workShare=$workShare, selfExitShare=$selfExitShare, " +
            "maxWork=$maxWork, selfExpiringLeases=$selfExpiringLeases, sessionBoundGrants=$sessionBoundGrants, " +
            "truncatedGrantShare=$truncatedGrantShare, meanInjectionInterval=$meanInjectionInterval, " +
            "groupPartitions=$groupPartitions, earlyCloseShare=$earlyCloseShare, backendHints=$backendHints)"

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
        internal var truncatedGrantShare: Double = 0.3
            private set
        internal var meanInjectionInterval: Duration = Duration.ofSeconds(20)
            private set
        internal var groupPartitions: Int = 3
            private set
        internal var earlyCloseShare: Double = 0.02
            private set
        internal var backendHints: Boolean = true
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

        fun truncatedGrantShare(truncatedGrantShare: Double) = apply { this.truncatedGrantShare = truncatedGrantShare }

        fun meanInjectionInterval(meanInjectionInterval: Duration) = apply {
            this.meanInjectionInterval = meanInjectionInterval
        }

        fun groupPartitions(groupPartitions: Int) = apply { this.groupPartitions = groupPartitions }

        fun earlyCloseShare(earlyCloseShare: Double) = apply { this.earlyCloseShare = earlyCloseShare }

        fun backendHints(backendHints: Boolean) = apply { this.backendHints = backendHints }

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
 * [InMemoryLeaseBackend], in virtual time, with random message delays and losses, process pauses, clock drift,
 * random start/stop/step-down operations, groups joined and left (partition elections follow each node's view),
 * nodes closed for good, grants reported shorter than granted, and backend-side faults (a session expired or a lease
 * deleted by the backend on its own). A fault phase is followed by a quiet phase (no losses, pauses, faults or
 * operations), then every election stops and every open node closes. [SimulationResult.events] counts the published
 * [me.ahoo.simba.event.SimbaEvent]s by class name and the exercised paths under `path:` keys.
 *
 * Node clocks run at rates that change over time (within [SimulationParams.clockRateSpread]). Checked after every
 * event, with side-effect-free backend reads ([InMemoryLeaseBackend.peekOwner], [InMemoryLeaseBackend.peekExpiry]):
 * - **I1 safety**: at most one valid term per name (partitions included); a valid term is the backend's owner, and
 *   its deadline, mapped to base time, never outlasts the backend's expiry of its lease (so it also holds between
 *   events); a term is valid when elected, and once seen invalid it never becomes valid again;
 * - **I2 monotonic**: tokens of a name strictly increase in the order terms are created;
 * (terms of a session the backend expired, or of a grant it deleted or lost, on its own are exempt from I1 and I2
 * until revoked; a lost grant restarts the name's tokens, which is counted as `premise:tokenReset` /
 * `premise:tokenRegression`, the backend breaking the premise of I2, not as a failure)
 * - **I3 pairing**: per node and name, `onElected` and `onRevoked` alternate for the same term; no callback after a
 *   stop completed (until the next start) or after close; at the end no term is open;
 * - **I4 liveness**: during the quiet phase, after [SimulationParams.livenessBound], every name and partition has a
 *   valid term; at any time, every lease the backend holds is accounted for, within two leases and two renew
 *   intervals, by its node's election (no leaked lease);
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
    private val horizon = quietEnd + params.config.leaseDuration.toNanos() * (DRAIN_LEASES + 1)
    private var faulty = true
    private val dropRate = random.nextDouble() * params.maxDropRate
    private val maxDelay = random.nextLong(MILLIS, params.maxDelay.toNanos() + 1)

    /** I4 starts after [SimulationParams.livenessBound], with this run's delay ceiling in place of the largest one. */
    private val livenessFrom = faultEnd + params.livenessBound.toNanos() - (params.maxDelay.toNanos() - maxDelay) * 2
    private val events = HashMap<String, Int>()

    private val names = List(random.nextInt(params.minNames, params.maxNames + 1)) { LeaseName("lease-$it") }
    private val nodes = List(random.nextInt(params.minNodes, params.maxNodes + 1)) { Node(it) }

    /** Reported remaining ÷ granted remaining for this run; below 1 the backend reports less than it grants. */
    private val truncation =
        if (random.nextDouble() < params.truncatedGrantShare) TRUNCATION_MIN + random.nextDouble() * TRUNCATION_SPAN else 1.0
    private val partitions = List(params.groupPartitions) { LeaseName("$GROUP.$it") }
    private val partitionNames = partitions.toHashSet()
    private val allNames = names + partitions

    /**
     * Sessions the backend expired and grants it deleted or lost on its own (name, token, session): terms of them,
     * including ones adopted later from a grant still in flight, are exempt from I1 and I2 until revoked.
     */
    private val expiredSessions = HashSet<SessionId>()
    private val removedGrants = HashSet<Triple<LeaseName, Long, SessionId>>()

    /** Base time at which each active term was first seen invalid (I1: it must never become valid again). */
    private val invalidSince = java.util.IdentityHashMap<EngineTerm, Long>()

    /** Backend owners not accounted for by an election of their node, with the base time first seen so (leaks). */
    private val unaccounted = HashMap<Pair<LeaseName, InMemoryLeaseBackend.Owner>, Long>()
    private val leakBound =
        params.config.leaseDuration.toNanos() * 2 + params.config.renewInterval.toNanos() * 2 + maxDelay * 4

    private val lastToken = HashMap<LeaseName, Long>()

    /** Per name, the highest token granted before the backend lost the lease: tokens at or below it regress. */
    private val resetFloor = HashMap<LeaseName, Long>()
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
        val ticker = VaryingTicker(clock, random.nextLong(0, OFFSET_RANGE), random, params.clockRateSpread, horizon)
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
        var inGroup = false

        val paused: Boolean get() = clock.nanoTime() < pausedUntil

        override fun toString(): String = "n$index"
    }

    fun run(): SimulationResult {
        if (params.backendHints) {
            backend.subscribe { hint ->
                nodes.forEach { node -> send { deliver(node, Event.Hint(hint)) } }
            }
        } else {
            count("path:noHintRun")
        }
        for (node in nodes) {
            schedule(random.nextLong(0, MILLIS * 50)) { deliver(node, Event.Boot) }
            for (name in names) {
                schedule(random.nextLong(0, MILLIS * 200)) { start(node, name) }
            }
        }
        if (truncation < 1.0) {
            count("path:truncatedGrantRun")
        }
        if (partitions.isNotEmpty()) {
            for (node in nodes) {
                if (random.nextDouble() < INITIAL_JOIN_SHARE) {
                    schedule(random.nextLong(0, MILLIS * 500)) { toggleGroup(node, join = true) }
                }
            }
        }
        schedulePoisson(params.meanPauseInterval) { pauseSomeone() }
        schedulePoisson(params.meanOperationInterval) { operateSomeone() }
        if (!params.meanInjectionInterval.isZero) {
            schedulePoisson(params.meanInjectionInterval) { injectBackendFault() }
        }
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
            checkLeaks()
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
            is Event.Close -> if (node.openTerms.isNotEmpty()) count("path:closeWhileLeading")
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
            is Effect.Members -> onView(node, effect)
        }
    }

    private fun call(node: Node, effect: Effect.Call) {
        checkHandover(node, effect.request)
        if (effect.timeout < engineConfig.renewInterval) {
            count("path:shortenedCallTimeout")
        }
        node.unresolved += effect.requestId
        after(effect.timeout) {
            if (node.unresolved.remove(effect.requestId)) {
                deliver(node, Event.Reply(effect.requestId, BackendResult.Failed(TimeoutException("timeout"))))
            }
        }
        send {
            val result = truncate(backend.execute(effect.request))
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
        if (!node.closeRequested && node.started.add(name)) {
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
        if (node.closeRequested) {
            return
        }
        if (random.nextDouble() < params.earlyCloseShare && nodes.count { !it.closeRequested } > 1) {
            closeEarly(node)
            return
        }
        if (partitions.isNotEmpty() && random.nextDouble() < GROUP_SHARE) {
            toggleGroup(node, join = !node.inGroup)
            return
        }
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

    /** Reports less remaining time than the backend grants, as a backend capping the session timeout does. */
    private fun truncate(result: BackendResult): BackendResult {
        if (truncation >= 1.0) {
            return result
        }
        fun scaled(remaining: Duration) = Duration.ofNanos((remaining.toNanos() * truncation).toLong())
        return when {
            result is BackendResult.Opened -> BackendResult.Opened(
                SessionGrant(result.grant.id, scaled(result.grant.remaining))
            )
            result is BackendResult.KeptAlive && result.result is KeepAliveResult.Live -> {
                val live = result.result as KeepAliveResult.Live
                BackendResult.KeptAlive(KeepAliveResult.Live(scaled(live.remaining), live.owned))
            }

            else -> result
        }
    }

    /** The backend expires a session that holds a lease, or deletes a held lease, on its own. */
    private fun injectBackendFault() {
        val held = allNames.mapNotNull { name -> backend.peekOwner(name)?.let { name to it } }
        if (held.isEmpty()) {
            return
        }
        val (name, owner) = held[random.nextInt(held.size)]
        val roll = random.nextDouble()
        when {
            roll < EXPIRY_SHARE -> {
                // Expired as the mode does it: with self-expiring leases the leases outlive the session.
                expiredSessions += owner.session
                count("path:injectSessionExpiry")
                record { "${millis(clock.nanoTime())} backend expires ${owner.session}" }
                backend.expireSession(owner.session)
            }

            roll < EXPIRY_SHARE + DELETE_SHARE -> {
                removedGrants += Triple(name, owner.token, owner.session)
                count("path:injectLeaseDelete")
                record { "${millis(clock.nanoTime())} backend deletes $name#${owner.token}" }
                backend.release(owner.session, mapOf(name to owner.token))
            }

            else -> {
                // The store loses the lease and its token (an evicted key, a failover that missed the write).
                removedGrants += Triple(name, owner.token, owner.session)
                count("premise:tokenReset")
                record { "${millis(clock.nanoTime())} backend loses $name#${owner.token}" }
                backend.loseLease(name)
                resetFloor[name] = maxOf(resetFloor[name] ?: 0, lastToken[name] ?: 0)
                lastToken[name] = 0
            }
        }
    }

    private fun toggleGroup(node: Node, join: Boolean) {
        if (node.closeRequested || node.inGroup == join) {
            return
        }
        node.inGroup = join
        if (join) {
            count("path:join")
            deliver(node, Event.Join(GROUP))
        } else {
            count("path:leave")
            deliver(node, Event.Leave(GROUP))
            node.started.filter { it in partitionNames }.forEach { stop(node, it) }
        }
    }

    /** Runs the partitions the node's latest view gives it, and stops the others (work first, then the lease). */
    private fun onView(node: Node, view: Effect.Members) {
        count("path:groupView")
        if (!node.inGroup || node.closeRequested) {
            return
        }
        val session = view.session
        val mine = if (session == null || session !in view.members) {
            emptySet()
        } else {
            PartitionAssignment.partitionsOf(session.value, partitions.size, view.members.map { it.value })
                .mapTo(HashSet()) { partitions[it] }
        }
        node.started.filter { it in partitionNames && it !in mine }.forEach {
            count("path:partitionHandedOver")
            stop(node, it)
        }
        mine.forEach { start(node, it) }
    }

    /** Closes [node] for good now, whatever its elections, work and pauses are doing. */
    private fun closeEarly(node: Node) {
        count("path:earlyClose")
        node.closeRequested = true
        node.inGroup = false
        node.started.clear()
        val done = CompletableFuture<Void>()
        node.waiting += done
        deliver(node, Event.Close(done))
    }

    private fun count(path: String) {
        events.merge(path, 1, Int::plus)
    }

    private fun quiet() {
        faulty = false
        record { "${millis(clock.nanoTime())} quiet phase" }
        for (node in nodes.filterNot { it.closeRequested }) {
            names.forEach { start(node, it) }
            if (partitions.isNotEmpty()) {
                toggleGroup(node, join = true)
            }
        }
    }

    private fun shutdown() {
        for (node in nodes.filterNot { it.closeRequested }) {
            node.inGroup = false
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
        if (!term.isValid) {
            fail("I1: $node elected $term, which is not valid")
        }
        node.openTerms[term.name] = term
        if (term.name in partitionNames) {
            count("path:partitionElected")
        }
        startWork(node, term)
        if (term.token <= 0) {
            fail("I2: $node elected non-positive token $term")
        }
        val last = lastToken[term.name] ?: 0
        if (compromised(term)) {
            // A grant still in flight when the backend deleted that lease (or expired its session) on its own can be
            // adopted after a later one: no client can tell before its next keep-alive, which revokes it.
            count("path:compromisedElected")
            return
        }
        if (term.token <= (resetFloor[term.name] ?: 0)) {
            // The backend lost the lease and restarted its tokens: I2 breaks by the backend's fault, not the engine's.
            count("premise:tokenRegression")
        }
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
        invalidSince.remove(term)
        val open = node.openTerms.remove(term.name)
        if (open !== term) {
            fail("I3: $node revoked $term but the open term was $open")
        }
        if (term.isValid || !term.revoked.toCompletableFuture().isDone) {
            fail("I3: $term still valid after revocation")
        }
    }

    private fun checkSafety() {
        checkNoRevival()
        for (name in allNames) {
            var valid: EngineTerm? = null
            var holder: Node? = null
            for (node in nodes) {
                val term = node.terms.active.firstOrNull { it.name == name && it.isValid && !compromised(it) } ?: continue
                if (valid != null) {
                    fail("I1: $holder holds $valid and $node holds $term, both valid")
                }
                valid = term
                holder = node
                val owner = backend.peekOwner(name)
                if (owner == null || owner.session != term.session || owner.token != term.token) {
                    fail("I1: $node holds valid $term of session ${term.session} but the backend owner is $owner")
                }
                checkDeadline(node, term)
            }
        }
    }

    /** I1: a term once seen invalid never becomes valid again. */
    private fun checkNoRevival() {
        val now = clock.nanoTime()
        for (node in nodes) {
            for (term in node.terms.active) {
                if (!term.isValid) {
                    invalidSince.putIfAbsent(term, now)
                } else if (term in invalidSince) {
                    val since = millis(invalidSince.getValue(term))
                    fail("I1: $node's $term became valid again after it was invalid at $since")
                }
            }
        }
    }

    /** I1 between events too: a valid term's deadline, in base time, must not outlast the backend's grant. */
    private fun checkDeadline(node: Node, term: EngineTerm) {
        val deadline = node.ticker.baseTimeOf(node.ticker.nanoTime() + term.remaining().toNanos())
        val expiry = backend.peekExpiry(term.name) ?: fail("I1: $node holds valid $term with no backend expiry")
        if (deadline - expiry > 0) {
            val late = Duration.ofNanos(deadline - expiry)
            fail("I1: $node's $term stays valid $late after the backend frees its lease")
        }
    }

    /** A term the backend broke its contract on (expired its session, deleted or lost its grant) until revoked. */
    private fun compromised(term: EngineTerm): Boolean =
        term.session in expiredSessions || Triple(term.name, term.token, term.session) in removedGrants

    /**
     * Leaks: every backend owner must be accounted for, within [leakBound], by its node's election in that session
     * (leader or handover with that token, an acquire in flight, or a release in flight) or by a session the backend
     * will soon expire. An orphan the engine never releases stays unaccounted and fails here.
     */
    private fun checkLeaks() {
        val now = clock.nanoTime()
        val seen = HashSet<Pair<LeaseName, InMemoryLeaseBackend.Owner>>()
        val leaks = allNames.mapNotNull { name -> backend.peekOwner(name)?.let { name to it } }
            .filterNot { (name, owner) -> accounted(name, owner) }
        for (key in leaks) {
            val (name, owner) = key
            seen += key
            val since = unaccounted.getOrPut(key) { now }
            if (now - since > leakBound) {
                fail("I4: $name is held by $owner for ${millis(now - since)} with no election accounting for it")
            }
        }
        unaccounted.keys.retainAll(seen)
    }

    private fun accounted(name: LeaseName, owner: InMemoryLeaseBackend.Owner): Boolean {
        val node = nodes.firstOrNull { (it.state.session as? SessionState.Active)?.id == owner.session } ?: return false
        val token = when (val election = node.state.elections[name]) {
            is ElectionState.Leader -> election.token
            is ElectionState.Handover -> election.token
            else -> null
        }
        if (token == owner.token) {
            return true
        }
        return node.state.pending.values.any { pending ->
            when (val request = pending.request) {
                is BackendRequest.Acquire -> name in request.names
                is BackendRequest.Release -> request.leases[name] == owner.token
                else -> false
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
        for (name in allNames) {
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
        const val GROUP = "group"
        const val GROUP_SHARE = 0.15
        const val INITIAL_JOIN_SHARE = 0.7
        const val EXPIRY_SHARE = 0.4
        const val DELETE_SHARE = 0.45
        const val TRUNCATION_MIN = 0.3
        const val TRUNCATION_SPAN = 0.65

        fun millis(nanos: Long): String = "%.3fs".format(nanos / 1e9)
    }
}

/**
 * A node clock whose rate changes over time: piecewise constant rates drawn from `[1 − spread, 1 + spread]`, each
 * held for 1–10 s of base time up to [horizon] (the last one holds after). Rates relative to the backend thus vary
 * within the drift bound, so margins are exercised rather than a single constant skew.
 */
private class VaryingTicker(
    private val base: Ticker,
    offset: Long,
    random: SplittableRandom,
    spread: Double,
    horizon: Long,
) : Ticker {
    private val starts: LongArray
    private val locals: LongArray
    private val rates: DoubleArray

    init {
        val starts = ArrayList<Long>()
        val locals = ArrayList<Long>()
        val rates = ArrayList<Double>()
        var at = 0L
        var local = offset
        while (true) {
            val rate = 1.0 + (random.nextDouble() * 2 - 1) * spread
            starts += at
            locals += local
            rates += rate
            if (at >= horizon) {
                break
            }
            val length = random.nextLong(SEGMENT_MIN, SEGMENT_MAX)
            at += length
            local += (length * rate).toLong()
        }
        this.starts = starts.toLongArray()
        this.locals = locals.toLongArray()
        this.rates = rates.toDoubleArray()
    }

    override fun nanoTime(): Long = localAt(base.nanoTime())

    fun localAt(baseNanos: Long): Long {
        val segment = segmentOf(starts, baseNanos)
        return locals[segment] + ((baseNanos - starts[segment]) * rates[segment]).toLong()
    }

    /** The earliest base time at which this clock reads at least [local]. */
    fun baseTimeOf(local: Long): Long {
        val segment = segmentOf(locals, local)
        var at = starts[segment] + ceil((local - locals[segment]) / rates[segment]).toLong()
        while (localAt(at) < local) {
            at++
        }
        while (at > 0 && localAt(at - 1) >= local) {
            at--
        }
        return at
    }

    private fun segmentOf(bounds: LongArray, value: Long): Int {
        val found = bounds.binarySearch(value)
        return if (found >= 0) found else maxOf(0, -found - 2)
    }

    private companion object {
        const val SEGMENT_MIN = 1_000_000_000L
        const val SEGMENT_MAX = 10_000_000_000L
    }
}
