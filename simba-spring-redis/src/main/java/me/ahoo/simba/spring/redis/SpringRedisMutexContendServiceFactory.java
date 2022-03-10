/*
 * Copyright [2021-2022] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

import me.ahoo.simba.core.MutexContendService;
import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.core.MutexContender;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring Redis Mutex Contend Service Factory .
 *
 * @author ahoo wang
 */
public class SpringRedisMutexContendServiceFactory implements MutexContendServiceFactory {
    private final Duration ttl;
    private final Duration transition;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final Executor handleExecutor;
    private final ScheduledExecutorService scheduledExecutorService;
    
    public SpringRedisMutexContendServiceFactory(
            Duration ttl,
            Duration transition,
            StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer) {
        this(ttl, transition, redisTemplate, listenerContainer, ForkJoinPool.commonPool(), Executors.newScheduledThreadPool(1));
    }
    
    public SpringRedisMutexContendServiceFactory(
            Duration ttl,
            Duration transition,
            StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            Executor handleExecutor,
            ScheduledExecutorService scheduledExecutorService) {
        this.ttl = ttl;
        this.transition = transition;
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.handleExecutor = handleExecutor;
        this.scheduledExecutorService = scheduledExecutorService;
    }
    
    @Override
    public MutexContendService createMutexContendService(MutexContender mutexContender) {
        return new SpringRedisMutexContendService(mutexContender, handleExecutor, ttl, transition, redisTemplate, listenerContainer, scheduledExecutorService);
    }
}
