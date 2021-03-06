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

package me.ahoo.simba.core;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Abstract Mutex Retrieval Service.
 *
 * @author ahoo wang
 */
@Slf4j
public abstract class AbstractMutexRetrievalService implements MutexRetrievalService {
    
    protected volatile boolean running;
    protected volatile MutexState mutexState = MutexState.NONE;
    protected final MutexRetriever mutexRetriever;
    protected final Executor handleExecutor;
    
    protected AbstractMutexRetrievalService(MutexRetriever mutexRetriever, Executor handleExecutor) {
        this.mutexRetriever = mutexRetriever;
        this.handleExecutor = handleExecutor;
    }
    
    @Override
    public MutexRetriever getRetriever() {
        return mutexRetriever;
    }
    
    @Override
    public MutexState getMutexState() {
        return mutexState;
    }
    
    protected void resetOwner() {
        this.mutexState = MutexState.NONE;
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public synchronized void start() {
        if (log.isInfoEnabled()) {
            log.info("start - mutex:[{}] - running:[{}]", mutexRetriever.getMutex(), running);
        }
        if (isRunning()) {
            return;
        }
        this.running = true;
        startRetrieval();
    }
    
    protected abstract void startRetrieval();
    
    protected abstract void stopRetrieval();
    
    protected CompletableFuture<Void> notifyOwner(MutexOwner newOwner) {
        return CompletableFuture.runAsync(() -> safeNotifyOwner(newOwner), handleExecutor);
    }
    
    protected void safeNotifyOwner(MutexOwner newOwner) {
        try {
            /*
             * Concurrency issues.
             * Order of assignment is very important.
             */
            final MutexState newState = new MutexState(getAfterOwner(), newOwner);
            this.mutexState = newState;
            getRetriever().notifyOwner(newState);
        } catch (Throwable throwable) {
            if (log.isErrorEnabled()) {
                log.error(throwable.getMessage(), throwable);
            }
        }
    }
    
    @Override
    public synchronized void stop() {
        if (log.isInfoEnabled()) {
            log.info("stop - mutex:[{}] - running:[{}]", mutexRetriever.getMutex(), running);
        }
        if (!isRunning()) {
            return;
        }
        this.running = false;
        stopRetrieval();
    }
    
    @Override
    public void close() throws Exception {
        stop();
    }
}
