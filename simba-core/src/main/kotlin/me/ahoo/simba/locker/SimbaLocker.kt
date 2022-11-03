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
package me.ahoo.simba.locker

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import java.util.concurrent.locks.LockSupport

/**
 *
 * Utility for safely acquiring a lock and releasing it using Java 7's
 * try-with-resource feature.
 *
 * Canonical usage:
 * ``` java
 * try ( Locker locker = new SimbaLocker(mutex, contendServiceFactory) )
 * {
 * locker.acquire(); //locker.acquire(timeout);
 * // do work
 * }
 *````
 * @author ahoo wang
 */
class SimbaLocker(
    mutex: String,
    contendServiceFactory: MutexContendServiceFactory
) : AbstractMutexContender(mutex), Locker {

    companion object {
        val OWNER: AtomicReferenceFieldUpdater<SimbaLocker, Thread> = AtomicReferenceFieldUpdater.newUpdater(
            SimbaLocker::class.java,
            Thread::class.java,
            "owner"
        )
    }

    private val contendService: MutexContendService

    @Volatile
    @Suppress("unused")
    private var owner: Thread? = null

    init {
        contendService = contendServiceFactory.createMutexContendService(this)
    }

    @Throws(Exception::class)
    override fun close() {
        contendService.stop()
    }

    override fun acquire() {
        if (OWNER.compareAndSet(this, null, Thread.currentThread())) {
            contendService.start()
            LockSupport.park(this)
        } else {
            throw IllegalMonitorStateException("Thread[${OWNER.get(this)}] already owns this lock[$mutex].")
        }
    }

    @Throws(TimeoutException::class)
    override fun acquire(timeout: Duration) {
        if (OWNER.compareAndSet(this, null, Thread.currentThread())) {
            contendService.start()
            LockSupport.parkNanos(this, timeout.toNanos())
            if (!contendService.isOwner) {
                throw TimeoutException(
                    "Could not acquire [$contenderId]@mutex:[$mutex] within timeout of %${timeout.toMillis()}ms"
                )
            }
        } else {
            throw IllegalMonitorStateException("Thread[${OWNER.get(this)}] already owns this lock[$mutex].")
        }
    }

    override fun onAcquired(mutexState: MutexState) {
        super.onAcquired(mutexState)
        LockSupport.unpark(OWNER[this])
    }

}
