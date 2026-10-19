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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimulationSpec {
    private val seeds = System.getProperty("simba.simulation.seeds")?.toLong() ?: DEFAULT_SEEDS
    private val only = System.getProperty("simba.simulation.seed")?.toLong()

    @Test
    fun `invariants hold under delays losses pauses and drift`() {
        val range = only?.let { it..it } ?: 0 until seeds
        var elections = 0L
        for (seed in range) {
            val result = Simulation(seed).run()
            elections += result.elections
        }
        elections.assert().isGreaterThan(0)
    }

    @Test
    fun `a seed replays exactly`() {
        Simulation(SAMPLE_SEED).run().assert().isEqualTo(Simulation(SAMPLE_SEED).run())
    }

    @Test
    fun `faults actually happen`() {
        val results = (0L until 50L).map { Simulation(it).run() }
        results.sumOf { it.revocations.size }.assert().isGreaterThan(0)
        results.sumOf { it.sessions - it.nodes }.assert().isGreaterThan(0)
    }

    @Test
    fun `the checker catches clocks drifting beyond the configured bound`() {
        val broken = SimulationParams().let { it.copy(clockRateSpread = 0.3, config = it.config.copy(drift = 0.0)) }
        assertThrows<SimulationFailure> {
            (0L until 200L).forEach { Simulation(it, broken).run() }
        }
    }

    companion object {
        const val DEFAULT_SEEDS = 10_000L
        const val SAMPLE_SEED = 42L
    }
}
