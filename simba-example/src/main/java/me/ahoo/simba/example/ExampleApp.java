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

import me.ahoo.simba.core.MutexContendService;
import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.locker.SimbaLocker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Example App.
 *
 * @author ahoo wang
 */
@SpringBootApplication
@EnableScheduling
@Slf4j
public class ExampleApp implements CommandLineRunner {
    
    public static void main(String[] args) {
        SpringApplication.run(ExampleApp.class);
    }
    
    @Autowired
    private MutexContendServiceFactory mutexContendServiceFactory;
    
    /**
     * Callback used to run the bean.
     *
     * @param args incoming main method arguments
     * @throws Exception on error
     */
    @Override
    public void run(String... args) throws Exception {
        MutexContendService contendService = mutexContendServiceFactory.createMutexContendService(new ExampleContender());
        contendService.start();
    }
    
    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void scheduleFixedDelay() {
        try (SimbaLocker locker = new SimbaLocker("example-locker-schedule-fixedDelay", this.mutexContendServiceFactory)) {
            locker.acquire(Duration.ofSeconds(1));
            log.warn("-------- scheduleFixedDelay - acquired:[{}] --------", locker.getContenderId());
            LockSupport.parkNanos(this, Duration.ofMillis(2100).toNanos());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
    
    @Scheduled(initialDelay = 1000, fixedRate = 1000)
    public void scheduleFixedRate() {
        try (SimbaLocker locker = new SimbaLocker("example-locker-schedule-fixedRate", this.mutexContendServiceFactory)) {
            locker.acquire(Duration.ofMillis(100));
            log.warn("-------- scheduleFixedRate - acquired:[{}] --------", locker.getContenderId());
            LockSupport.parkNanos(this, Duration.ofMillis(2100).toNanos());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
}
