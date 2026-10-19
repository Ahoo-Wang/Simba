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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class ScheduleSpecTest {
    private val second = Duration.ofSeconds(1).toNanos()

    @Test
    fun `fixed delay counts from the end of the run`() {
        val schedule = Schedule.fixedDelay(Duration.ofSeconds(1))
        schedule.next(scheduledAt = 0, endedAt = 300).assert().isEqualTo(300 + second)
    }

    @Test
    fun `fixed rate keeps the grid and skips missed starts`() {
        val schedule = Schedule.fixedRate(Duration.ofSeconds(1))
        schedule.next(scheduledAt = 0, endedAt = 300).assert().isEqualTo(second)
        schedule.next(scheduledAt = 0, endedAt = second).assert().isEqualTo(2 * second)
        schedule.next(scheduledAt = 0, endedAt = 2 * second + 1).assert().isEqualTo(3 * second)
    }

    @Test
    fun `schedules validate their durations and compare by value`() {
        assertThrows<IllegalArgumentException> { Schedule.fixedDelay(Duration.ZERO) }
        assertThrows<IllegalArgumentException> { Schedule.fixedRate(Duration.ofSeconds(1), Duration.ofSeconds(-1)) }
        val schedule = Schedule.fixedRate(Duration.ofSeconds(1)).withInitialDelay(Duration.ofSeconds(2))
        schedule.assert().isEqualTo(Schedule.fixedRate(Duration.ofSeconds(1), Duration.ofSeconds(2)))
        schedule.hashCode().assert().isEqualTo(
            Schedule.fixedRate(Duration.ofSeconds(1), Duration.ofSeconds(2)).hashCode()
        )
        schedule.toString().assert().contains("FIXED_RATE")
    }
}
