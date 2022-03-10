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

package me.ahoo.simba.spring.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.core.AbstractMutexContender;
import me.ahoo.simba.core.MutexState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RedisMutexContendServiceTest .
 *
 * @author ahoo wang
 */
@Slf4j
class SpringRedisMutexContendServiceTest {
    private SpringRedisMutexContendServiceFactory contendServiceFactory;
    RedisMessageListenerContainer listenerContainer;
    
    @BeforeEach
    void setup() {
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory(redisStandaloneConfiguration);
        lettuceConnectionFactory.afterPropertiesSet();
        StringRedisTemplate stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(lettuceConnectionFactory);
        listenerContainer.afterPropertiesSet();
        contendServiceFactory = new SpringRedisMutexContendServiceFactory(Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                stringRedisTemplate,
                listenerContainer,
                ForkJoinPool.commonPool(),
                Executors.newScheduledThreadPool(1)
        );
    }
    
    
    @Test
    void start() {
        
        CompletableFuture<MutexState> acquiredFuture = new CompletableFuture();
        CompletableFuture<MutexState> releasedFuture = new CompletableFuture();
        
        SpringRedisMutexContendService contendService = (SpringRedisMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender("start") {
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
        SpringRedisMutexContendService contendService = (SpringRedisMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender("restart") {
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
        
        SpringRedisMutexContendService contendService = (SpringRedisMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender("guard") {
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
        /*
         **** Very important ****
         */
        listenerContainer.start();
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
        List<SpringRedisMutexContendService> contendServiceList = new ArrayList<>(10);
        AtomicReference<String> currentOwnerIdRef = new AtomicReference<>();
        for (int i = 0; i < 10; i++) {
            SpringRedisMutexContendService contendService = (SpringRedisMutexContendService) contendServiceFactory.createMutexContendService(new AbstractMutexContender("multiContend") {
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
            /*
             **** Very important ****
             */
            listenerContainer.start();
            contendServiceList.add(contendService);
        }
        TimeUnit.SECONDS.sleep(30);
        assertEquals(1, count.get());
        String currentOwnerId = currentOwnerIdRef.get();
        for (SpringRedisMutexContendService contendService : contendServiceList) {
            if (contendService.getAfterOwner().getOwnerId() != null) {
                Assertions.assertEquals(contendService.getAfterOwner().getOwnerId(), currentOwnerId);
            }
        }
        assertEquals(1, contendServiceList.stream().filter(contendService -> contendService.getContenderId().equals(currentOwnerId)).count());
    }
}
