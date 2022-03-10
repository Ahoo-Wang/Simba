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

package me.ahoo.simba.jdbc;

import me.ahoo.simba.core.MutexContendService;
import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.core.MutexContender;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Jdbc Mutex Contend Service Factory.
 *
 * @author ahoo wang
 */
public class JdbcMutexContendServiceFactory implements MutexContendServiceFactory {
    private final MutexOwnerRepository mutexOwnerRepository;
    private final Executor handleExecutor;
    private final Duration initialDelay;
    private final Duration ttl;
    private final Duration transition;
    
    public JdbcMutexContendServiceFactory(MutexOwnerRepository mutexOwnerRepository,
                                          Duration initialDelay,
                                          Duration ttl,
                                          Duration transition) {
        this(mutexOwnerRepository, ForkJoinPool.commonPool(), initialDelay, ttl, transition);
    }
    
    public JdbcMutexContendServiceFactory(MutexOwnerRepository mutexOwnerRepository,
                                          Executor handleExecutor,
                                          Duration initialDelay,
                                          Duration ttl,
                                          Duration transition) {
        this.mutexOwnerRepository = mutexOwnerRepository;
        this.handleExecutor = handleExecutor;
        this.initialDelay = initialDelay;
        this.ttl = ttl;
        this.transition = transition;
    }
    
    @Override
    public MutexContendService createMutexContendService(MutexContender mutexContender) {
        return new JdbcMutexContendService(mutexContender, handleExecutor, mutexOwnerRepository, initialDelay, ttl, transition);
    }
    
    
}
