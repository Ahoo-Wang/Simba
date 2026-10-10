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

package me.ahoo.simba.example;

import me.ahoo.simba.schedule.ScheduleContext;
import me.ahoo.simba.spring.boot.starter.scheduling.SimbaScheduled;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Example Scheduler: runs on the leader of {@code example-scheduler} only, started and stopped by the starter.
 *
 * @author ahoo wang
 */
@Service
@Slf4j
public class ExampleScheduler {

    @SimbaScheduled(mutex = "example-scheduler", fixedDelay = "10s", worker = "ExampleScheduler")
    public void work(ScheduleContext context) throws InterruptedException {
        if (log.isInfoEnabled()) {
            log.info("do some work start! fencingToken:[{}]", context.getFencingToken());
        }
        TimeUnit.SECONDS.sleep(5);
        if (log.isInfoEnabled()) {
            log.info("do some work end!");
        }
    }
}
