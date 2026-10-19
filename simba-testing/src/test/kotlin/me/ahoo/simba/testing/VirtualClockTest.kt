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
import java.time.Duration

class VirtualClockTest {
    @Test
    fun `a virtual clock only moves forward`() {
        val clock = VirtualClock(10)
        clock.advance(Duration.ofNanos(5))
        clock.nanoTime().assert().isEqualTo(15L)
        clock.advanceTo(20)
        clock.nanoTime().assert().isEqualTo(20L)
        assertThrows<IllegalArgumentException> { clock.advance(Duration.ofNanos(-1)) }
        assertThrows<IllegalArgumentException> { clock.advanceTo(19) }
    }

    @Test
    fun `a drifting ticker maps local deadlines back to base time`() {
        val clock = VirtualClock()
        listOf(0.99, 1.0, 1.01, 1.3).forEach { rate ->
            val ticker = DriftingTicker(clock, offset = 1_000, rate = rate)
            ticker.offset.assert().isEqualTo(1_000L)
            ticker.rate.assert().isEqualTo(rate)
            listOf(1_000L, 1_001L, 123_456_789L).forEach { local ->
                val base = ticker.baseTimeOf(local)
                (ticker.localAt(base) >= local).assert().isTrue()
                (ticker.localAt(base - 1) < local).assert().isTrue()
            }
        }
        clock.advanceTo(100)
        DriftingTicker(clock, offset = 5, rate = 2.0).nanoTime().assert().isEqualTo(205L)
        assertThrows<IllegalArgumentException> { DriftingTicker(clock, offset = 0, rate = 0.0) }
    }
}
