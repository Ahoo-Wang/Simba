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

import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.schedule.AbstractScheduler;
import me.ahoo.simba.schedule.ScheduleConfig;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;


/**
 * Example Scheduler.
 *
 * @author ahoo wang
 */
@Service
@Slf4j
public class ExampleScheduler extends AbstractScheduler implements SmartLifecycle {

    public ExampleScheduler(MutexContendServiceFactory contendServiceFactory) {
        super("example-scheduler", contendServiceFactory);
    }

    @Override
    protected String getWorker() {
        return "ExampleScheduler";
    }

    @SneakyThrows
    @Override
    protected void work() {
        if (log.isInfoEnabled()) {
            log.info("do some work start!");
        }
        TimeUnit.SECONDS.sleep(5);
        if (log.isInfoEnabled()) {
            log.info("do some work end!");
        }
    }

    @Override
    public boolean isRunning() {
        return getRunning();
    }

    @NotNull
    @Override
    protected ScheduleConfig getConfig() {
        return ScheduleConfig.delay(Duration.ofSeconds(0), Duration.ofSeconds(10));
    }
}
