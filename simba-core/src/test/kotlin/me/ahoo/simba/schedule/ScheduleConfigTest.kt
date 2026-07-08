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

package me.ahoo.simba.schedule

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test
import java.time.Duration

class ScheduleConfigTest {
    @Test
    fun `rate factory sets FIXED_RATE strategy`() {
        val config = ScheduleConfig.rate(Duration.ofMillis(10), Duration.ofMillis(100))

        assertThat(config.strategy, equalTo(ScheduleConfig.Strategy.FIXED_RATE))
        assertThat(config.initialDelay, equalTo(Duration.ofMillis(10)))
        assertThat(config.period, equalTo(Duration.ofMillis(100)))
    }

    @Test
    fun `delay factory sets FIXED_DELAY strategy`() {
        val config = ScheduleConfig.delay(Duration.ofMillis(10), Duration.ofMillis(100))

        assertThat(config.strategy, equalTo(ScheduleConfig.Strategy.FIXED_DELAY))
    }

    @Test
    fun `data class equals and copy`() {
        val config = ScheduleConfig.rate(Duration.ofMillis(10), Duration.ofMillis(100))
        val copy = config.copy()

        assertThat(copy, equalTo(config))
        assertThat(copy, not(sameInstance(config)))
    }

    @Test
    fun `data class component destructuring`() {
        val config = ScheduleConfig.delay(Duration.ofMillis(5), Duration.ofMillis(50))

        val (strategy, initialDelay, period) = config
        assertThat(strategy, equalTo(ScheduleConfig.Strategy.FIXED_DELAY))
        assertThat(initialDelay, equalTo(Duration.ofMillis(5)))
        assertThat(period, equalTo(Duration.ofMillis(50)))
    }

    @Test
    fun `Strategy enum has FIXED_DELAY then FIXED_RATE in order`() {
        val values = ScheduleConfig.Strategy.values()

        assertThat(values.size, equalTo(2))
        assertThat(values[0], equalTo(ScheduleConfig.Strategy.FIXED_DELAY))
        assertThat(values[1], equalTo(ScheduleConfig.Strategy.FIXED_RATE))
    }
}
