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

package me.ahoo.simba.redis;

import io.lettuce.core.RedisClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.core.AbstractMutexContender;
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
class RedisMutexContendServiceTest {
    private RedisMutexContendServiceFactory contendServiceFactory;

    @BeforeAll
    void setup() {
        contendServiceFactory = new RedisMutexContendServiceFactory(Duration.ofSeconds(2), Duration.ofSeconds(1),createClient().connect().reactive(), createClient().connectPubSub().reactive());
    }

    public static RedisClient createClient() {
        return RedisClient.create("redis://localhost:6379");
    }


    @Test
    void start() {

        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();

        RedisMutexContendService contendService = (RedisMutexContendService)contendServiceFactory.createMutexContendService(new AbstractMutexContender("start") {
            @Override
            public void onAcquired(MutexState mutexState) {
                log.info("onAcquired");
                acquiredFuture.complete(mutexState);
            }

            @Override
            public void onReleased(MutexState mutexState) {
                log.info("onReleased");
                releasedFuture.complete(mutexState);
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

        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();

        CompletableFuture<MutexState> acquiredFuture2 = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture2 = new CompletableFuture();
        RedisMutexContendService contendService =(RedisMutexContendService)contendServiceFactory.createMutexContendService(new AbstractMutexContender("restart") {
            @Override
            public void onAcquired(MutexState mutexState) {
                log.info("onAcquired");
                if (!acquiredFuture.isDone()) {
                    acquiredFuture.complete(mutexState);
                } else {
                    acquiredFuture2.complete(mutexState);
                }
            }

            @Override
            public void onReleased(MutexState mutexState) {
                log.info("onReleased");
                if (!releasedFuture.isDone()) {
                    releasedFuture.complete(mutexState);
                } else {
                    releasedFuture2.complete(mutexState);
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
        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();

        RedisMutexContendService contendService =(RedisMutexContendService)contendServiceFactory.createMutexContendService(new AbstractMutexContender("guard") {
            @Override
            public void onAcquired(MutexState mutexState) {
                log.info("onAcquired");
                acquiredFuture.complete(mutexState);
            }

            @Override
            public void onReleased(MutexState mutexState) {
                log.info("onReleased");
                releasedFuture.complete(mutexState);
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

        AtomicInteger count = new AtomicInteger(0);
        List<RedisMutexContendService> contendServiceList = new ArrayList<>(10);
        AtomicReference<String> currentOwnerIdRef = new AtomicReference<>();
        for (int i = 0; i < 10; i++) {
            RedisMutexContendService contendService =(RedisMutexContendService)contendServiceFactory.createMutexContendService(new AbstractMutexContender("multiContend") {
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
        for (RedisMutexContendService contendService : contendServiceList) {
            if (contendService.getAfterOwner().getOwnerId() != null) {
                Assertions.assertEquals(contendService.getAfterOwner().getOwnerId(), currentOwnerId);
            }
        }
        assertEquals(1, contendServiceList.stream().filter(contendService -> contendService.getContenderId().equals(currentOwnerId)).count());
    }
}
