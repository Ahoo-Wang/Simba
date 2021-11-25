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

package me.ahoo.simba.locker;

import com.google.common.base.Strings;
import me.ahoo.simba.core.*;

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

    protected final static AtomicReferenceFieldUpdater<SimbaLocker, Thread> OWNER =
            AtomicReferenceFieldUpdater.newUpdater(SimbaLocker.class, Thread.class, "owner");

    public SimbaLocker(String mutex, MutexContendServiceFactory contendServiceFactory) {
        super(mutex);
        contendService = contendServiceFactory.createMutexContendService(this);
    }

    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * <p>While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p> Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p><em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>Note that unlike the {@link Closeable#close close}
     * method of {@link Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     *
     * @throws Exception if this resource cannot be closed
     */
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
