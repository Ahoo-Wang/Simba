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

package me.ahoo.simba.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.core.AbstractMutexContender;
import me.ahoo.simba.core.MutexOwner;
import me.ahoo.simba.core.MutexState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author ahoo wang
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMutexContendServiceTest {
    private JdbcMutexOwnerRepository jdbcMutexOwnerRepository;
    private JdbcMutexContendServiceFactory contendServiceFactory;

    @BeforeAll
    void setup() {
        HikariDataSource hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl("jdbc:mysql://localhost:3306/simba_db");
        hikariDataSource.setUsername("root");
        hikariDataSource.setPassword("root");
        jdbcMutexOwnerRepository = new JdbcMutexOwnerRepository(hikariDataSource);
        contendServiceFactory = new JdbcMutexContendServiceFactory(jdbcMutexOwnerRepository, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @Test
    void start() {
        String mutex = "start";
        CompletableFuture<MutexOwner> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexOwner> releasedFuture = new CompletableFuture();

        JdbcMutexContendService contendService = (JdbcMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
            @Override
            public void onAcquired(MutexState mutexState) {
                log.info("onAcquired");
                acquiredFuture.complete(mutexState.getAfter());
            }

            @Override
            public void onReleased(MutexState mutexState) {
                log.info("onReleased");
                releasedFuture.complete(mutexState.getAfter());
            }
        });

        contendService.start();
        acquiredFuture.join();
        assertTrue(contendService.isOwner());
        contendService.stop();
        releasedFuture.join();
        assertFalse(contendService.isOwner());

    }


    @Test
    void restart() {
        String mutex = "restart";
        jdbcMutexOwnerRepository.tryInitMutex(mutex);
        CompletableFuture<MutexOwner> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexOwner> releasedFuture = new CompletableFuture();

        CompletableFuture<MutexOwner> acquiredFuture2 = new CompletableFuture();
        CompletableFuture<MutexOwner> releasedFuture2 = new CompletableFuture();
        JdbcMutexContendService contendService = (JdbcMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
            @Override
            public void onAcquired(MutexState mutexState) {
                log.info("onAcquired");
                if (!acquiredFuture.isDone()) {
                    acquiredFuture.complete(mutexState.getAfter());
                } else {
                    acquiredFuture2.complete(mutexState.getAfter());
                }
            }

            @Override
            public void onReleased(MutexState mutexState) {
                log.info("onReleased");
                if (!releasedFuture.isDone()) {
                    releasedFuture.complete(mutexState.getAfter());
                } else {
                    releasedFuture2.complete(mutexState.getAfter());
                }
            }
        });

        contendService.start();
        acquiredFuture.join();
        assertTrue(contendService.isOwner());
        contendService.stop();
        releasedFuture.join();
        assertFalse(contendService.isOwner());

        contendService.start();
        acquiredFuture2.join();
        assertTrue(contendService.isOwner());
        contendService.stop();
        releasedFuture2.join();
        assertFalse(contendService.isOwner());
    }

    @SneakyThrows
    @Test
    void guard() {
        String mutex = "guard";
        jdbcMutexOwnerRepository.tryInitMutex(mutex);
        CompletableFuture<MutexOwner> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexOwner> releasedFuture = new CompletableFuture();

        JdbcMutexContendService contendService = (JdbcMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
            @Override
            public void onAcquired(MutexState mutexState) {
                log.info("onAcquired");
                acquiredFuture.complete(mutexState.getAfter());
            }

            @Override
            public void onReleased(MutexState mutexState) {
                log.info("onReleased");
                releasedFuture.complete(mutexState.getAfter());
            }
        });

        contendService.start();
        acquiredFuture.join();
        assertTrue(contendService.isOwner());
        TimeUnit.SECONDS.sleep(3);
        assertEquals(contendService.getAfterOwner().getOwnerId(), contendService.getContender().getContenderId());
        assertTrue(contendService.isOwner());
        contendService.stop();
        releasedFuture.join();
        assertFalse(contendService.isOwner());
    }

    @Test
    void multiContend() throws InterruptedException {
        String mutex = "multiContend";
        jdbcMutexOwnerRepository.tryInitMutex(mutex);
        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<String> currentOwnerIdRef = new AtomicReference<>();
        List<JdbcMutexContendService> contendServiceList = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            JdbcMutexContendService contendService = (JdbcMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
                @Override
                public void onAcquired(MutexState mutexState) {
                    currentOwnerIdRef.set(mutexState.getAfter().getOwnerId());
                    super.onAcquired(mutexState);
                    assertEquals(1, count.incrementAndGet());
                }

                @Override
                public void onReleased(MutexState mutexState) {
                    super.onReleased(mutexState);
                    assertEquals(0, count.decrementAndGet());
                }
            });
            contendService.start();
            contendServiceList.add(contendService);
        }
        TimeUnit.SECONDS.sleep(30);
        assertEquals(1, count.get());
        String currentOwnerId = currentOwnerIdRef.get();
        for (JdbcMutexContendService contendService : contendServiceList) {
            if (contendService.getAfterOwner().getOwnerId() != null){
                Assertions.assertEquals(contendService.getAfterOwner().getOwnerId(), currentOwnerId);
            }
        }
        assertEquals(1, contendServiceList.stream().filter(contendService -> contendService.getContenderId().equals(currentOwnerId)).count());
    }
}
