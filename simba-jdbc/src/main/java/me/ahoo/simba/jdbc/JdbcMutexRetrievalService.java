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


import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.core.AbstractMutexRetrievalService;
import me.ahoo.simba.core.ContendPeriod;
import me.ahoo.simba.core.MutexRetriever;
import me.ahoo.simba.util.Threads;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author ahoo wang
 */
@Slf4j
public class JdbcMutexRetrievalService extends AbstractMutexRetrievalService {

    private final MutexOwnerRepository mutexOwnerRepository;

    private final Duration initialDelay;
    private final Duration ttl;
    private ScheduledThreadPoolExecutor executorService;
    private ScheduledFuture<?> contendScheduledFuture;

    public JdbcMutexRetrievalService(MutexRetriever mutexRetriever,
                                     Executor handleExecutor,
                                     MutexOwnerRepository mutexOwnerRepository,
                                     Duration initialDelay,
                                     Duration ttl) {
        super(mutexRetriever, handleExecutor);
        this.mutexOwnerRepository = mutexOwnerRepository;
        this.initialDelay = initialDelay;
        this.ttl = ttl;
    }

    @Override
    protected void startRetrieval() {
        this.executorService = new ScheduledThreadPoolExecutor(1, Threads.defaultFactory("JdbcMutexRetrievalService"));
        nextSchedule(initialDelay.toMillis());
    }

    private void nextSchedule(long nextDelay) {
        if (log.isDebugEnabled()) {
            log.debug("nextSchedule - mutex:[{}] - nextDelay:[{}].", getMutex(), nextDelay);
        }
        contendScheduledFuture = this.executorService.schedule(this::safeRetrieval, nextDelay, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void stopRetrieval() {
        if (contendScheduledFuture != null) {
            contendScheduledFuture.cancel(true);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    private void safeRetrieval() {
        try {
            MutexOwnerEntity mutexOwner = mutexOwnerRepository.ensureOwner(getMutex());
            notifyOwner(mutexOwner)
                    .whenComplete((nil, err) -> {
                        if (err != null) {
                            if (log.isErrorEnabled()) {
                                log.error(err.getMessage(), err);
                            }
                        }
                        long nextDelay = ContendPeriod.nextContenderDelay(mutexOwner);
                        nextSchedule(nextDelay);
                    });
        } catch (Throwable throwable) {
            if (log.isErrorEnabled()) {
                log.error(throwable.getMessage(), throwable);
            }
            nextSchedule(ttl.toMillis());
        }
    }
}
