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

import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** A [Ticker] that only moves when told to. */
class VirtualClock(start: Long = 0) : Ticker {
    private val now = AtomicLong(start)

    override fun nanoTime(): Long = now.get()

    fun advance(duration: Duration) {
        require(!duration.isNegative) { "Time never moves backwards." }
        now.addAndGet(duration.toNanos())
    }

    fun advanceTo(nanos: Long) {
        now.updateAndGet { current ->
            require(nanos >= current) { "Time never moves backwards: $nanos < $current." }
            nanos
        }
    }
}

/**
 * A node's local clock: [offset] plus [base] time scaled by [rate]. With `rate` in `[1 − drift, 1 + drift]` it
 * models a clock whose rate differs from the backend's by at most `drift`.
 */
class DriftingTicker(private val base: Ticker, val offset: Long, val rate: Double) : Ticker {
    init {
        require(rate > 0) { "rate must be positive." }
    }

    override fun nanoTime(): Long = localAt(base.nanoTime())

    fun localAt(baseNanos: Long): Long = offset + (baseNanos * rate).toLong()

    /** The earliest base time at which this clock reads at least [local]. */
    fun baseTimeOf(local: Long): Long {
        var base = ceil((local - offset) / rate).toLong()
        while (localAt(base) < local) {
            base++
        }
        while (localAt(base - 1) >= local) {
            base--
        }
        return base
    }
}
