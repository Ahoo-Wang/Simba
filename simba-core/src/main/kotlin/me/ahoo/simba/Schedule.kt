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

import java.time.Duration

/**
 * When leader work runs: first [initialDelay] after the term began, then every [period] — measured between the end
 * of one run and the start of the next ([Mode.FIXED_DELAY]), or between scheduled starts ([Mode.FIXED_RATE]; a run
 * that overruns skips the missed starts instead of bursting).
 */
class Schedule private constructor(val mode: Mode, val period: Duration, val initialDelay: Duration) {
    init {
        require(!period.isNegative && !period.isZero) { "period must be positive." }
        require(!initialDelay.isNegative) { "initialDelay must not be negative." }
    }

    enum class Mode { FIXED_DELAY, FIXED_RATE }

    /** The same schedule starting [initialDelay] after the term began. */
    fun withInitialDelay(initialDelay: Duration): Schedule = Schedule(mode, period, initialDelay)

    /** The start (monotonic nanoseconds) after the run scheduled at [scheduledAt] that ended at [endedAt]. */
    internal fun next(scheduledAt: Long, endedAt: Long): Long {
        val period = period.toNanos()
        return when (mode) {
            Mode.FIXED_DELAY -> endedAt + period
            Mode.FIXED_RATE -> {
                val next = scheduledAt + period
                if (endedAt - next < 0) next else scheduledAt + period * ((endedAt - scheduledAt) / period + 1)
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Schedule && mode == other.mode && period == other.period && initialDelay == other.initialDelay

    override fun hashCode(): Int = (mode.hashCode() * 31 + period.hashCode()) * 31 + initialDelay.hashCode()

    override fun toString(): String = "Schedule(mode=$mode, period=$period, initialDelay=$initialDelay)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun fixedDelay(delay: Duration, initialDelay: Duration = Duration.ZERO): Schedule =
            Schedule(Mode.FIXED_DELAY, delay, initialDelay)

        @JvmStatic
        @JvmOverloads
        fun fixedRate(period: Duration, initialDelay: Duration = Duration.ZERO): Schedule =
            Schedule(Mode.FIXED_RATE, period, initialDelay)
    }
}
