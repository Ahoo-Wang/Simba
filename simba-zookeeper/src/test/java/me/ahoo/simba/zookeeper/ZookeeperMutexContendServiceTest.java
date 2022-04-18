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

package me.ahoo.simba.zookeeper;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import me.ahoo.simba.core.AbstractMutexContender;
import me.ahoo.simba.core.MutexState;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
class ZookeeperMutexContendServiceTest {
    
    private CuratorFramework curatorFramework;
    private ZookeeperMutexContendServiceFactory contendServiceFactory;
    TestingServer testingServer;
    
    @SneakyThrows
    @BeforeAll
    void setup() {
        testingServer = new TestingServer();
        testingServer.start();
        curatorFramework = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), new RetryNTimes(1, 10));
        curatorFramework.start();
        this.contendServiceFactory = new ZookeeperMutexContendServiceFactory(curatorFramework);
    }
    
    @SneakyThrows
    @AfterAll
    void after() {
        if (Objects.nonNull(curatorFramework)) {
            curatorFramework.close();
        }
        if (Objects.nonNull(testingServer)) {
            testingServer.stop();
        }
    }
    
    @Test
    void start() {
        String mutex = "start";
        
        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();
        
        ZookeeperMutexContendService contendService = (ZookeeperMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
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
        String mutex = "restart";
        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();
        
        CompletableFuture<MutexState> acquiredFuture2 = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture2 = new CompletableFuture();
        ZookeeperMutexContendService contendService = (ZookeeperMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
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
        String mutex = "guard";
        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();
        
        ZookeeperMutexContendService contendService = (ZookeeperMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
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
        assertTrue(acquiredFuture.join().isOwner(contendService.getContenderId()));
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
        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<String> currentOwnerIdRef = new AtomicReference<>();
        List<ZookeeperMutexContendService> contendServiceList = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            ZookeeperMutexContendService contendService = (ZookeeperMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
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
        for (ZookeeperMutexContendService contendService : contendServiceList) {
            if (contendService.hasOwner()) {
                Assertions.assertEquals(contendService.getAfterOwner().getOwnerId(), currentOwnerId);
            }
        }
        assertEquals(1, contendServiceList.stream().filter(contendService -> contendService.getContenderId().equals(currentOwnerId)).count());
    }
    
}
