/*
 * Copyright [2021-2021] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

package me.ahoo.simba.schedule;

import java.time.Duration;

/**
 * @author ahoo wang
 */
public class ScheduleConfig {

    private final Strategy strategy;
    private final Duration initialDelay;
    private final Duration period;

    public ScheduleConfig(Strategy strategy, Duration initialDelay, Duration period) {
        this.strategy = strategy;
        this.initialDelay = initialDelay;

        this.period = period;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }


    public Duration getPeriod() {
        return period;
    }

    public static ScheduleConfig ofRate(Duration initialDelay, Duration period) {
        return new ScheduleConfig(Strategy.FIXED_RATE, initialDelay, period);
    }

    public static ScheduleConfig ofDelay(Duration initialDelay, Duration period) {
        return new ScheduleConfig(Strategy.FIXED_DELAY, initialDelay, period);
    }

    public enum Strategy {
        FIXED_DELAY,
        FIXED_RATE
    }
}
