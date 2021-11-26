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

package me.ahoo.simba.schedule;

import me.ahoo.simba.core.AbstractMutexContender;
import me.ahoo.simba.core.MutexContendService;
import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.core.MutexState;
import me.ahoo.simba.util.Threads;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author ahoo wang
 */
public abstract class AbstractScheduler {

    private final String mutex;
    private final ScheduleConfig config;
    private final MutexContendService contendService;

    public AbstractScheduler(String mutex, ScheduleConfig config, MutexContendServiceFactory contendServiceFactory) {
        this.mutex = mutex;
        this.config = config;
        this.contendService = contendServiceFactory.createMutexContendService(new WorkContender(mutex));
    }

    public String getMutex() {
        return mutex;
    }

    protected abstract String getWorker();

    protected abstract void work();

    public void start() {
        this.contendService.start();
    }

    public void stop() {
        this.contendService.stop();
    }

    public boolean isRunning() {
        return this.contendService.isRunning();
    }

    public class WorkContender extends AbstractMutexContender {
        private final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;
        private volatile ScheduledFuture<?> workFuture;

        public WorkContender(String mutex) {
            super(mutex);
            this.scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1, Threads.defaultFactory(getWorker()));
        }

        @Override
        public void onAcquired(MutexState mutexState) {
            super.onAcquired(mutexState);
            if (this.workFuture == null || this.workFuture.isCancelled() || this.workFuture.isDone()) {
                long initialDelay = config.getInitialDelay().toMillis();
                long period = config.getPeriod().toMillis();
                if (ScheduleConfig.Strategy.FIXED_RATE.equals(config.getStrategy())) {
                    this.workFuture = scheduledThreadPoolExecutor.scheduleAtFixedRate(AbstractScheduler.this::work, initialDelay, period, TimeUnit.MILLISECONDS);
                } else {
                    this.workFuture = scheduledThreadPoolExecutor.scheduleWithFixedDelay(AbstractScheduler.this::work, initialDelay, period, TimeUnit.MILLISECONDS);
                }
            }
        }

        @Override
        public void onReleased(MutexState mutexState) {
            super.onReleased(mutexState);
            if (this.workFuture != null) {
                this.workFuture.cancel(true);
            }
        }
    }

}
