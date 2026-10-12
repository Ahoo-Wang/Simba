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

import me.ahoo.simba.core.LeaseExpiredException
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.checkLease
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 *
 * Utility for safely acquiring a lock and releasing it using Java 7's
 * try-with-resource feature.
 *
 * Canonical usage:
 * ``` java
 * try ( Locker locker = new SimbaLocker(mutex, contendServiceFactory) )
 * {
 *    locker.acquire(); //locker.acquire(timeout);
 *    // do work
 * }
 *```
 * @author ahoo wang
 */
interface Locker : AutoCloseable {
    fun acquire()

    @Throws(TimeoutException::class)
    fun acquire(timeout: Duration)

    /**
     * Fencing token of the held lock, [MutexOwner.NO_FENCING_TOKEN] when not held or unsupported by the backend.
     */
    val fencingToken: Long
        get() = MutexOwner.NO_FENCING_TOKEN

    /**
     * How long the held lock stays valid, [Duration.ZERO] when not held; see [MutexContendService.leaseRemaining].
     */
    val leaseRemaining: Duration
        get() = Duration.ZERO

    /**
     * Throws [LeaseExpiredException] unless the lock is held with some lease left.
     */
    fun ensureLease() {
        ensureLease(Duration.ZERO)
    }

    /**
     * Throws [LeaseExpiredException] unless [leaseRemaining] exceeds [minRemaining].
     */
    fun ensureLease(minRemaining: Duration) {
        checkLease(leaseRemaining, minRemaining) { "locker" }
    }
}
