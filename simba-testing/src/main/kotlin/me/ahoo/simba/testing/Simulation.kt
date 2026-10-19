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
import java.util.PriorityQueue
import java.util.SplittableRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

/**
 * Knobs of a [Simulation]. Durations are virtual.
 *
 * @param maxDelay each run draws its fault-phase delay ceiling from `[1ms, maxDelay]`; above half the renew interval,
 *   replies arrive after the call timed out.
 * @param maxDropRate each run draws its fault-phase loss rate from `[0, maxDropRate]`.
 * @param quietMaxDelay delay ceiling of the quiet phase.
 * @param clockRateSpread node clock rates are drawn from `[1 − spread, 1 + spread]`; the engine is only safe while
 *   the spread does not exceed [SimbaConfig.drift], which is what the checker's self-test relies on.
 */
data class SimulationParams(
    val config: SimbaConfig = SimbaConfig(
        leaseDuration = Duration.ofSeconds(3),
        renewInterval = Duration.ofSeconds(1),
        drift = 0.01,
        margin = Duration.ofMillis(20),
        retryJitter = Duration.ofMillis(500),
        minBackoff = Duration.ofMillis(50),
    ),
    val minNodes: Int = 2,
    val maxNodes: Int = 5,
    val minNames: Int = 1,
    val maxNames: Int = 3,
    val maxDelay: Duration = Duration.ofMillis(700),
    val maxDropRate: Double = 0.3,
    val quietMaxDelay: Duration = Duration.ofMillis(50),
    val clockRateSpread: Double = config.drift,
    val meanPauseInterval: Duration = Duration.ofSeconds(8),
    val maxPause: Duration = config.leaseDuration.multipliedBy(2),
    val meanOperationInterval: Duration = Duration.ofSeconds(2),
    val faultPhase: Duration = Duration.ofSeconds(60),
    val quietPhase: Duration = Duration.ofSeconds(30),
) {
    /**
     * I4 bound, measured from the start of the quiet phase: fault-phase messages land within two delays, a cooldown
     * or a stale session may block the lease for one lease duration each, then a candidate retries within one renew
     * interval and a round trip.
     */
    val livenessBound: Duration =
        maxDelay.multipliedBy(2) + config.leaseDuration.multipliedBy(2) + config.renewInterval.multipliedBy(2) +
            quietMaxDelay.multipliedBy(4)
}

/** What a run did, for sanity checks and replay comparisons. */
data class SimulationResult(
    val seed: Long,
    val nodes: Int,
    val names: Int,
    val tasks: Long,
    val elections: Int,
    val revocations: Map<RevokeReason, Int>,
    val highestTokens: Map<LeaseName, Long>,
    val sessions: Int,
    val events: Map<String, Int>,
)

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
 * - **I4 liveness**: during the quiet phase, after [SimulationParams.livenessBound], every name has a valid term.
 *
 * Everything random comes from [seed], so a failing seed replays exactly.
 */
class Simulation(val seed: Long, private val params: SimulationParams = SimulationParams()) {
    private val random = SplittableRandom(seed)
    private val clock = VirtualClock()
    private var sessionCounter = 0
    private val backend = InMemoryLeaseBackend(clock) { SessionId("s${++sessionCounter}") }
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
    private val revocations = HashMap<RevokeReason, Int>()

    private class Task(val at: Long, val seq: Long, val action: () -> Unit)

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
        val stopped = HashSet<LeaseName>()
        val openTerms = HashMap<LeaseName, EngineTerm>()
        val waiting = ArrayList<CompletableFuture<Void>>()
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
        if (event is Event.Start) {
            node.stopped -= event.name
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
            is Effect.Revoked -> revoked(node, node.terms.revoked(effect), effect.reason)
            is Effect.Complete -> {
                if (effect.done in node.stopFutures) {
                    node.stopFutures -= effect.done
                    effect.name?.let { node.stopped += it }
                }
                if (node.closeRequested && effect.name == null) {
                    node.closed = true
                }
                effect.done.complete(null)
            }

            is Effect.Publish -> events.merge(effect.event.javaClass.simpleName, 1, Int::plus)
        }
    }

    private fun call(node: Node, effect: Effect.Call) {
        node.unresolved += effect.requestId
        after(engineConfig.renewInterval) {
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
            deliver(node, Event.Start(name))
        }
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
        if (term.token <= 0) {
            fail("I2: $node elected non-positive token $term")
        }
        val last = lastToken[term.name] ?: 0
        if (term.token <= last) {
            fail("I2: $node elected $term after token $last was already granted")
        }
        lastToken[term.name] = term.token
    }

    private fun revoked(node: Node, term: EngineTerm, reason: RevokeReason) {
        revocations.merge(reason, 1, Int::plus)
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
