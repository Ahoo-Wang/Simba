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

import java.util.Objects
import java.util.concurrent.TimeUnit

/**
 * Mutex Owner: an immutable observation of a lease.
 *
 * Equality covers the lease facts ([ownerId], [acquiredAt], [ttlAt], [transitionAt], [fencingToken]); the
 * observation time does not. Timestamps are epoch milliseconds in the backend's clock. [currentAt] advances that
 * clock from [observedAt] with the local monotonic clock, so lease decisions never compare wall clocks of
 * different nodes.
 *
 * @param ownerId contender id of the owner; [NONE_OWNER_ID] when there is none.
 * @param acquiredAt when the lease was acquired.
 * @param ttlAt when the owner should renew.
 * @param transitionAt when the lease ends; during `ttlAt..transitionAt` only the owner may renew.
 * @param fencingToken strictly increasing per ownership term, stable within one; [NO_FENCING_TOKEN] when the
 * backend does not issue tokens (ADR 0002).
 * @param observedAt backend time at which this owner was observed.
 * @param observedNanos local `System.nanoTime()` at the same moment.
 *
 * @author ahoo wang
 */
class MutexOwner @JvmOverloads constructor(
    val ownerId: String,
    val acquiredAt: Long = System.currentTimeMillis(),
    val ttlAt: Long = Long.MAX_VALUE,
    val transitionAt: Long = Long.MAX_VALUE,
    val fencingToken: Long = NO_FENCING_TOKEN,
    val observedAt: Long = System.currentTimeMillis(),
    private val observedNanos: Long = System.nanoTime()
) {
    fun isOwner(contenderId: String): Boolean {
        return ownerId == contenderId
    }

    /**
     * The backend's current time, estimated from [observedAt] and the local monotonic clock.
     */
    val currentAt: Long
        get() = observedAt + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observedNanos)

    val isInTtl: Boolean
        get() = ttlAt > currentAt

    fun isInTtl(contenderId: String): Boolean {
        return isOwner(contenderId) && isInTtl
    }

    /**
     * Whether the lease is still running (`transitionAt >= currentAt`).
     */
    fun hasOwner(): Boolean {
        return transitionAt >= currentAt
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutexOwner) return false
        return ownerId == other.ownerId &&
            acquiredAt == other.acquiredAt &&
            ttlAt == other.ttlAt &&
            transitionAt == other.transitionAt &&
            fencingToken == other.fencingToken
    }

    override fun hashCode(): Int = Objects.hash(ownerId, acquiredAt, ttlAt, transitionAt, fencingToken)

    override fun toString(): String {
        return "MutexOwner(ownerId='$ownerId', acquiredAt=$acquiredAt, ttlAt=$ttlAt, transitionAt=$transitionAt, " +
            "fencingToken=$fencingToken, observedAt=$observedAt)"
    }

    companion object {
        const val NONE_OWNER_ID = ""
        const val NO_FENCING_TOKEN = 0L

        @JvmField
        val NONE = MutexOwner(NONE_OWNER_ID, 0, 0, 0)
    }
}
