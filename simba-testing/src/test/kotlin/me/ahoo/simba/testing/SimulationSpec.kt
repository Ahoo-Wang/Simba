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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.stream.LongStream

class SimulationSpec {
    private val seeds = System.getProperty("simba.simulation.seeds")?.toLong() ?: DEFAULT_SEEDS
    private val only = System.getProperty("simba.simulation.seed")?.toLong()

    @Test
    fun `invariants hold under delays losses pauses and drift`() {
        runSeeds(SimulationParams()).assert().isGreaterThan(0)
    }

    @Test
    fun `invariants hold when leases expire on their own`() {
        runSeeds(SELF_EXPIRING).assert().isGreaterThan(0)
    }

    @Test
    fun `invariants hold when self-expiring grants last only as long as the session has left`() {
        runSeeds(SESSION_BOUND).assert().isGreaterThan(0)
    }

    @Test
    fun `invariants hold without backend hints, as on JDBC`() {
        runSeeds(NO_HINTS).assert().isGreaterThan(0)
    }

    /**
     * Runs every seed (or only `-Psimba.simulation.seed`) and returns the elections they held. Seeds are independent and
     * deterministic (virtual time, one random source each), so they run in parallel; a failure still names its seed.
     */
    private fun runSeeds(params: SimulationParams): Long {
        val range = only?.let { LongStream.rangeClosed(it, it) } ?: LongStream.range(0, seeds)
        return range.parallel().map { seed -> Simulation(seed, params).run().elections.toLong() }.sum()
    }

    @Test
    fun `groups joined again while leaving still run their partitions`() {
        // Seeds that left a partition without a leader before joins during a leave were fixed (#649).
        Simulation(217, SELF_EXPIRING).run()
        Simulation(200, NO_HINTS).run()
        Simulation(1286).run()
        Simulation(4026).run()
    }

    @Test
    fun `a seed replays exactly`() {
        Simulation(SAMPLE_SEED).run().assert().isEqualTo(Simulation(SAMPLE_SEED).run())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["owned", "self-expiring", "session-bound", "no-hints"])
    fun `every fault path actually happens`(mode: String) {
        val params = when (mode) {
            "self-expiring" -> SELF_EXPIRING
            "session-bound" -> SESSION_BOUND
            "no-hints" -> NO_HINTS
            else -> SimulationParams()
        }
        val results = (0L until COVERAGE_SEEDS).map { Simulation(it, params).run() }
        val revocations = results.flatMap { it.revocations.entries }.groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
        val events = results.flatMap { it.events.entries }.groupBy(
            { it.key },
            { it.value }
        ).mapValues { it.value.sum() }
        // Every way a term ends, the reconcile revocation (TAKEN_OVER) and a session expiring mid-term included.
        RevokeReason.entries.forEach { reason -> (revocations[reason] ?: 0).assert().isGreaterThan(0) }
        listOf(
            "SessionLeaseShortened",
            "path:truncatedGrantRun",
            "path:shortenedCallTimeout",
            "path:injectSessionExpiry",
            "path:injectLeaseDelete",
            "path:join",
            "path:leave",
            "path:groupView",
            "path:partitionElected",
            "path:partitionHandedOver",
            "path:earlyClose",
            "path:closeWhileLeading",
            // The backend losing a lease restarts its tokens: I2 breaks by the backend's fault, counted, not failed.
            "premise:tokenReset",
        ).forEach { path -> (events[path] ?: 0).assert().isGreaterThan(0) }
        if (mode == "no-hints") {
            (events["path:noHintRun"] ?: 0).assert().isEqualTo(COVERAGE_SEEDS.toInt())
        }
        results.sumOf { it.sessions - it.nodes }.assert().isGreaterThan(0)
    }

    @Test
    fun `handovers wait for work and some time out`() {
        val results = (0L until 50L).map { Simulation(it).run() }
        results.sumOf { it.handovers }.assert().isGreaterThan(0)
        results.sumOf { it.handoverTimeouts }.assert().isGreaterThan(0)
        (results.sumOf { it.handovers } > results.sumOf { it.handoverTimeouts }).assert().isTrue()
    }

    @Test
    fun `the checker catches a term outliving its lease between events`() {
        // No drift allowance and no margin, but clocks running up to 30% fast: deadlines outlast the backend's grant.
        val config = SimulationParams().config.toBuilder().drift(0.0).margin(Duration.ZERO).build()
        val broken = SimulationParams.builder().config(config).clockRateSpread(0.3).groupPartitions(0)
            .meanInjectionInterval(Duration.ZERO).build()
        val failure = assertThrows<SimulationFailure> {
            (0L until 200L).forEach { Simulation(it, broken).run() }
        }
        failure.message.assert().contains("after the backend frees its lease")
    }

    @Test
    fun `the checker catches clocks drifting beyond the configured bound`() {
        val broken =
            SimulationParams.builder().config(
                SimulationParams().config.toBuilder().drift(0.0).build()
            ).clockRateSpread(0.3).build()
        assertThrows<SimulationFailure> {
            (0L until 200L).forEach { Simulation(it, broken).run() }
        }
    }

    companion object {
        const val DEFAULT_SEEDS = 10_000L
        const val SAMPLE_SEED = 42L
        const val COVERAGE_SEEDS = 50L
        val SELF_EXPIRING = SimulationParams.builder().selfExpiringLeases(true).build()
        val NO_HINTS = SimulationParams.builder().backendHints(false).build()
        val SESSION_BOUND = SimulationParams.builder().selfExpiringLeases(true).sessionBoundGrants(true).build()
    }
}
