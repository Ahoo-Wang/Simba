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

package me.ahoo.simba

import java.time.Duration

/**
 * Session timing.
 *
 * The local deadline of a session is `sentAt + remaining × (1 − drift) − margin`, measured from when the keep-alive
 * was *sent*; it is always reached before the backend expires the session as long as the local clock rate is within
 * `1 ± drift` of the backend's.
 *
 * @param leaseDuration the backend expires a session this long after its last keep-alive.
 * @param renewInterval keep-alive period; at most half of [leaseDuration] so that at least two attempts fit before
 *   the deadline.
 * @param drift upper bound of the clock rate difference between this node and the backend.
 * @param margin extra safety subtracted from every deadline.
 * @param retryJitter candidates retry after `renewInterval / 2` plus a random delay in `[0, retryJitter)`.
 * @param minBackoff lower bound of the halving backoff after a failed keep-alive.
 */
data class SimbaConfig(
    val leaseDuration: Duration = Duration.ofSeconds(15),
    val renewInterval: Duration = leaseDuration.dividedBy(3),
    val drift: Double = 0.01,
    val margin: Duration = Duration.ofMillis(50),
    val retryJitter: Duration = renewInterval.dividedBy(2),
    val minBackoff: Duration = Duration.ofMillis(100),
) {
    init {
        require(!renewInterval.isNegative && !renewInterval.isZero) { "renewInterval must be positive." }
        require(renewInterval.multipliedBy(2) <= leaseDuration) {
            "renewInterval[$renewInterval] × 2 must not exceed leaseDuration[$leaseDuration]."
        }
        require(drift >= 0.0 && drift < MAX_DRIFT) { "drift must be in [0, $MAX_DRIFT), was $drift." }
        require(!margin.isNegative) { "margin must not be negative." }
        require(!retryJitter.isNegative) { "retryJitter must not be negative." }
        require(!minBackoff.isNegative && !minBackoff.isZero) { "minBackoff must be positive." }
        require(localValidity() > renewInterval.toNanos()) {
            "leaseDuration × (1 − drift) − margin must exceed renewInterval, or every term would flap."
        }
    }

    /** Local validity granted by a fresh keep-alive, in nanoseconds. */
    internal fun localValidity(): Long = (leaseDuration.toNanos() * (1.0 - drift)).toLong() - margin.toNanos()

    companion object {
        const val MAX_DRIFT = 0.5
    }
}
