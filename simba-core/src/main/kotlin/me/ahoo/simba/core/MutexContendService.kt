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
package me.ahoo.simba.core

import java.time.Duration

/**
 * Mutex Contend Service.
 *
 * @author ahoo wang
 */
interface MutexContendService : MutexRetrievalService {
    /**
     * 当前竞争服务绑定的竞争者.
     *
     * @return MutexContender
     */
    val contender: MutexContender
    val contenderId: String
        get() = contender.contenderId

    /**
     * 当前竞争者是否是持有者.
     *
     * @return boolean
     */
    val isOwner: Boolean
        get() = afterOwner.isOwner(contenderId)
    val isInTtl: Boolean
        get() = afterOwner.isInTtl(contenderId)

    /**
     * Fencing token of the current term while this contender owns the mutex, otherwise
     * [MutexOwner.NO_FENCING_TOKEN]. Pass it to protected resources so they can reject stale owners.
     */
    val fencingToken: Long
        get() = afterOwner.let { if (it.isOwner(contenderId)) it.fencingToken else MutexOwner.NO_FENCING_TOKEN }

    /**
     * How long this contender's local ownership stays valid: [Duration.ZERO] when it does not own the mutex,
     * [UNBOUNDED_LEASE] when the backend does not bound ownership in time (Zookeeper).
     *
     * A hint for whether to *start* the next step of work, not a guarantee to finish it: a pause can outlast the
     * remainder, so writes still carry the [fencingToken] (ADR 0007).
     */
    val leaseRemaining: Duration
        get() {
            val owner = afterOwner
            if (!owner.isOwner(contenderId)) {
                return Duration.ZERO
            }
            val remainingMillis = owner.transitionAt - owner.currentAt
            if (remainingMillis >= UNBOUNDED_LEASE.toMillis()) {
                return UNBOUNDED_LEASE
            }
            return Duration.ofMillis(remainingMillis.coerceAtLeast(0))
        }

    /**
     * Throws [LeaseExpiredException] unless this contender owns the mutex with some lease left.
     */
    fun ensureLease() {
        ensureLease(Duration.ZERO)
    }

    /**
     * Throws [LeaseExpiredException] unless [leaseRemaining] exceeds [minRemaining], e.g. the expected duration of
     * the next step.
     */
    fun ensureLease(minRemaining: Duration) {
        checkLease(leaseRemaining, minRemaining) { "mutex:[$mutex] contenderId:[$contenderId]" }
    }

    companion object {
        /**
         * [leaseRemaining] of ownership that is not bounded in time.
         */
        @JvmField
        val UNBOUNDED_LEASE: Duration = Duration.ofNanos(Long.MAX_VALUE)
    }
}
