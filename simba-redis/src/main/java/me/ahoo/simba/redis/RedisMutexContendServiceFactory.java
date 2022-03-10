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

package me.ahoo.simba.redis;

import me.ahoo.simba.core.MutexContendService;
import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.core.MutexContender;

import io.lettuce.core.api.reactive.RedisScriptingReactiveCommands;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Redis Mutex Contend Service Factory.
 *
 * @author ahoo wang
 */
public class RedisMutexContendServiceFactory implements MutexContendServiceFactory {
    private final Executor handleExecutor;
    private final Duration ttl;
    private final Duration transition;
    private final RedisScriptingReactiveCommands<String, String> redisCommands;
    private final RedisPubSubReactiveCommands<String, String> redisPubSubCommands;
    
    public RedisMutexContendServiceFactory(Duration ttl, Duration transition, RedisScriptingReactiveCommands<String, String> redisCommands,
                                           RedisPubSubReactiveCommands<String, String> redisPubSubCommands) {
        this(ForkJoinPool.commonPool(), ttl, transition, redisCommands, redisPubSubCommands);
    }
    
    public RedisMutexContendServiceFactory(Executor handleExecutor, Duration ttl, Duration transition, RedisScriptingReactiveCommands<String, String> redisCommands,
                                           RedisPubSubReactiveCommands<String, String> redisPubSubCommands) {
        this.handleExecutor = handleExecutor;
        this.ttl = ttl;
        this.transition = transition;
        this.redisCommands = redisCommands;
        this.redisPubSubCommands = redisPubSubCommands;
    }
    
    @Override
    public MutexContendService createMutexContendService(MutexContender mutexContender) {
        return new RedisMutexContendService(mutexContender, handleExecutor, ttl, transition, redisCommands, redisPubSubCommands);
    }
}
