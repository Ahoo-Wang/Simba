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


import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.core.AbstractMutexContendService;
import me.ahoo.simba.core.ContendPeriod;
import me.ahoo.simba.core.MutexContender;
import me.ahoo.simba.core.MutexOwner;
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
public class JdbcMutexContendService extends AbstractMutexContendService {

    private final MutexOwnerRepository mutexOwnerRepository;

    private final Duration initialDelay;
    private final Duration ttl;
    private final Duration transition;
    private ScheduledThreadPoolExecutor executorService;
    private final ContendPeriod contendPeriod;
    private volatile ScheduledFuture<?> contendScheduledFuture;

    public JdbcMutexContendService(MutexContender mutexContender,
                                   Executor handleExecutor,
                                   MutexOwnerRepository mutexOwnerRepository,
                                   Duration initialDelay,
                                   Duration ttl,
                                   Duration transition) {
        super(mutexContender, handleExecutor);
        this.contendPeriod = new ContendPeriod(this.getContenderId());
        this.mutexOwnerRepository = mutexOwnerRepository;
        this.initialDelay = initialDelay;
        this.ttl = ttl;
        this.transition = transition;
    }

    @Override
    protected void startContend() {
        executorService = new ScheduledThreadPoolExecutor(1, Threads.defaultFactory(Strings.lenientFormat("JdbcSimba_%s_%s", getMutex(), getContenderId())));
        nextSchedule(initialDelay.toMillis());
    }

    private void nextSchedule(long nextDelay) {
        if (log.isDebugEnabled()) {
            log.debug("nextSchedule - mutex:[{}] contenderId:[{}] - nextDelay:[{}].", getMutex(), getContenderId(), nextDelay);
        }
        contendScheduledFuture = this.executorService.schedule(this::safeHandleContend, nextDelay, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void stopContend() {
        if (contendScheduledFuture != null) {
            contendScheduledFuture.cancel(true);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        notifyOwner(MutexOwner.NONE);
        mutexOwnerRepository.release(getMutex(), getContenderId());
    }

    private void safeHandleContend() {
        try {
            final MutexOwner mutexOwner = this.contend();
            notifyOwner(mutexOwner);
            long nextDelay = contendPeriod.ensureNextDelay(mutexOwner);
            nextSchedule(nextDelay);
        } catch (Throwable throwable) {
            if (log.isErrorEnabled()) {
                log.error(throwable.getMessage(), throwable);
            }
            nextSchedule(ttl.toMillis());
        }
    }

    /**
     * 服务实例竞争领导权
     */
    private MutexOwner contend() {
        final MutexOwnerEntity mutexOwner = mutexOwnerRepository.acquireAndGetOwner(getMutex(), getContenderId(), ttl.toMillis(), transition.toMillis());
        if (log.isDebugEnabled()) {
            log.debug("contend - mutex:[{}] contenderId:[{}] - succeeded:[{}].", getMutex(), getContenderId(), mutexOwner.isOwner(getContenderId()));
        }
        return mutexOwner;
    }
}
