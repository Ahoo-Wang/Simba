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

import me.ahoo.simba.Election;
import me.ahoo.simba.LockTerm;
import me.ahoo.simba.LockTimeoutException;
import me.ahoo.simba.Simba;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Example App: one election with callbacks, two locks taken from Spring scheduled methods, and the leader-only work
 * of {@link ExampleScheduler} and {@link ExamplePartitioned}, all on the one {@link Simba} client of the starter.
 *
 * @author ahoo wang
 */
@SpringBootApplication
@EnableScheduling
@Slf4j
public class ExampleApp implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApp.class, args);
    }

    private final Simba simba;
    private Election election;

    public ExampleApp(Simba simba) {
        this.simba = simba;
    }

    @Override
    public void run(String... args) {
        election = simba.election("example-election");
        election.start(new ExampleLeader());
    }

    /**
     * Stops the election on context close, before the starter closes the client; {@code stop()} completes once
     * {@code onRevoked} ran.
     */
    @EventListener(ContextClosedEvent.class)
    public void stopElection() {
        if (election != null) {
            election.stop().toCompletableFuture().join();
        }
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void scheduleFixedDelay() {
        withLock("example-lock-fixed-delay", Duration.ofSeconds(1));
    }

    @Scheduled(initialDelay = 1000, fixedRate = 1000)
    public void scheduleFixedRate() {
        withLock("example-lock-fixed-rate", Duration.ofMillis(100));
    }

    /**
     * The {@link LockTerm} is the credential: pass its token to the protected resource, which rejects lower ones.
     */
    private void withLock(String name, Duration timeout) {
        try (LockTerm term = simba.lock(name).acquire(timeout)) {
            log.warn("-------- [{}] acquired, token:[{}] --------", name, term.getToken());
            LockSupport.parkNanos(this, Duration.ofMillis(2100).toNanos());
        } catch (LockTimeoutException e) {
            log.info("[{}] is held elsewhere: {}", name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
