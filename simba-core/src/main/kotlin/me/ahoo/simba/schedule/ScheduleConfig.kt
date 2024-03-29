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

import java.time.Duration

/**
 * Schedule Config.
 *
 * @author ahoo wang
 */
data class ScheduleConfig(val strategy: Strategy, val initialDelay: Duration, val period: Duration) {

    enum class Strategy {
        FIXED_DELAY, FIXED_RATE
    }

    companion object {
        @JvmStatic
        fun rate(initialDelay: Duration, period: Duration): ScheduleConfig {
            return ScheduleConfig(Strategy.FIXED_RATE, initialDelay, period)
        }

        @JvmStatic
        fun delay(initialDelay: Duration, period: Duration): ScheduleConfig {
            return ScheduleConfig(Strategy.FIXED_DELAY, initialDelay, period)
        }
    }
}
