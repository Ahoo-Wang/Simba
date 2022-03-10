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

package me.ahoo.simba.locker;

import me.ahoo.simba.core.AbstractMutexContender;
import me.ahoo.simba.core.MutexContendService;
import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.core.MutexState;

import com.google.common.base.Strings;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;

/**
 * <p>
 * Utility for safely acquiring a lock and releasing it using Java 7's
 * try-with-resource feature.
 * </p>
 *
 * <p>
 * Canonical usage:
 * <code>
 * try ( Locker locker = new SimbaLocker(mutex, contendServiceFactory) )
 * {
 * locker.acquire(); //locker.acquire(timeout);
 * // do work
 * }
 * </code>
 * </p>
 *
 * @author ahoo wang
 */
public class SimbaLocker extends AbstractMutexContender implements Locker {
    
    private final MutexContendService contendService;
    private volatile Thread owner;
    
    protected static final AtomicReferenceFieldUpdater<SimbaLocker, Thread> OWNER =
        AtomicReferenceFieldUpdater.newUpdater(SimbaLocker.class, Thread.class, "owner");
    
    public SimbaLocker(String mutex, MutexContendServiceFactory contendServiceFactory) {
        super(mutex);
        contendService = contendServiceFactory.createMutexContendService(this);
    }

    @Override
    public void close() throws Exception {
        contendService.stop();
    }
    
    @Override
    public void acquire() {
        if (OWNER.compareAndSet(this, null, Thread.currentThread())) {
            contendService.start();
            LockSupport.park(this);
        } else {
            throw new IllegalMonitorStateException();
        }
    }
    
    @Override
    public void acquire(Duration timeout) throws TimeoutException {
        if (OWNER.compareAndSet(this, null, Thread.currentThread())) {
            contendService.start();
            LockSupport.parkNanos(this, timeout.toNanos());
            if (!contendService.isOwner()) {
                throw new TimeoutException(Strings.lenientFormat("Could not acquire [%s]@mutex:[%s] within timeout of %sms", getContenderId(), getMutex(), timeout.toMillis()));
            }
        } else {
            throw new IllegalMonitorStateException();
        }
    }
    
    @Override
    public void onAcquired(MutexState mutexState) {
        super.onAcquired(mutexState);
        LockSupport.unpark(OWNER.get(this));
    }
    
}
