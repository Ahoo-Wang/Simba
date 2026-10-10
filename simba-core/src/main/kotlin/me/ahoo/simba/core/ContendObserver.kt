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

/**
 * Receives contention events, e.g. to record metrics. Every method defaults to a no-op; implementations must be
 * fast and thread-safe, and exceptions they throw are logged and ignored.
 *
 * Events are reported per mutex. Zookeeper elects through Curator's `LeaderLatch` and reports ownership and work
 * events only, no [onContend] or [onLeaseExpired].
 *
 * @author ahoo wang
 */
interface ContendObserver {
    /**
     * [service] started contending (its `start()` succeeded); e.g. to track the services of this process.
     */
    fun onStarted(service: MutexContendService) = Unit

    /**
     * [service] stopped, after its release was delivered.
     */
    fun onStopped(service: MutexContendService) = Unit

    /**
     * A contention round trip to the backend: acquiring when [renew] is `false`, renewing a held lease otherwise.
     */
    fun onContend(mutex: String, renew: Boolean, durationNanos: Long, outcome: ContendOutcome) = Unit

    /**
     * This contender became the owner of [mutex].
     */
    fun onAcquired(mutex: String) = Unit

    /**
     * This contender stopped owning [mutex] (released, revoked, lost or stopped).
     */
    fun onReleased(mutex: String) = Unit

    /**
     * The held lease of [mutex] ended without a successful renewal, so local ownership was revoked
     * (followed by [onReleased]).
     */
    fun onLeaseExpired(mutex: String) = Unit

    /**
     * A run of leader-only scheduled work ended.
     */
    fun onWork(mutex: String, durationNanos: Long, outcome: WorkOutcome) = Unit

    companion object {
        @JvmField
        val NOOP: ContendObserver = object : ContendObserver {}
    }
}

/**
 * Result of a contention round trip.
 */
enum class ContendOutcome {
    /** This contender holds the lease. */
    OWNER,

    /** Another contender holds the lease. */
    OTHER,

    /** The backend call failed. */
    FAILED
}

/**
 * Result of a scheduled work run.
 */
enum class WorkOutcome {
    SUCCESS,
    FAILED,

    /** Cancelled on leadership loss or scheduler stop. */
    INTERRUPTED
}
