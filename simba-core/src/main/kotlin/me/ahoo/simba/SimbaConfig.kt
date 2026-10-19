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
import java.util.Objects

/**
 * Session timing, built with [builder].
 *
 * The local deadline of a session is `sentAt + remaining × (1 − drift) − margin`, measured from when the keep-alive
 * was *sent*; it is always reached before the backend expires the session as long as the local clock rate is within
 * `1 ± drift` of the backend's.
 *
 * A plain immutable class with value equality and no public constructor, so later versions can add settings without
 * breaking callers; derive a variant with [toBuilder].
 *
 * @property leaseDuration the backend expires a session this long after its last keep-alive (default 15 s).
 * @property renewInterval keep-alive period (default `leaseDuration / 3`); at most half of [leaseDuration] so that at
 *   least two attempts fit before the deadline. A backend granting less than [leaseDuration] makes the client renew
 *   more often (see `SimbaEvent.SessionLeaseShortened`).
 * @property drift upper bound of the clock rate difference between this node and the backend (default 0.01).
 * @property margin extra safety subtracted from every deadline (default 50 ms).
 * @property retryJitter candidates retry after `renewInterval / 2` plus a random delay in `[0, retryJitter)`
 *   (default `renewInterval / 2`).
 * @property minBackoff lower bound of the halving backoff after a failed keep-alive (default 100 ms).
 */
class SimbaConfig private constructor(
    val leaseDuration: Duration,
    val renewInterval: Duration,
    val drift: Double,
    val margin: Duration,
    val retryJitter: Duration,
    val minBackoff: Duration,
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

    /** A builder holding every value of this config, as set; derived values are kept, not derived again. */
    fun toBuilder(): Builder =
        Builder()
            .leaseDuration(leaseDuration)
            .renewInterval(renewInterval)
            .drift(drift)
            .margin(margin)
            .retryJitter(retryJitter)
            .minBackoff(minBackoff)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SimbaConfig &&
            leaseDuration == other.leaseDuration &&
            renewInterval == other.renewInterval &&
            drift == other.drift &&
            margin == other.margin &&
            retryJitter == other.retryJitter &&
            minBackoff == other.minBackoff

    override fun hashCode(): Int = Objects.hash(leaseDuration, renewInterval, drift, margin, retryJitter, minBackoff)

    override fun toString(): String =
        "SimbaConfig(leaseDuration=$leaseDuration, renewInterval=$renewInterval, drift=$drift, margin=$margin, " +
            "retryJitter=$retryJitter, minBackoff=$minBackoff)"

    /** Builds a [SimbaConfig]; [renewInterval] and [retryJitter] default to values derived from the values set. */
    class Builder internal constructor() {
        private var leaseDuration: Duration = DEFAULT_LEASE
        private var renewInterval: Duration? = null
        private var drift: Double = DEFAULT_DRIFT
        private var margin: Duration = DEFAULT_MARGIN
        private var retryJitter: Duration? = null
        private var minBackoff: Duration = DEFAULT_MIN_BACKOFF

        fun leaseDuration(leaseDuration: Duration) = apply { this.leaseDuration = leaseDuration }

        fun renewInterval(renewInterval: Duration) = apply { this.renewInterval = renewInterval }

        fun drift(drift: Double) = apply { this.drift = drift }

        fun margin(margin: Duration) = apply { this.margin = margin }

        fun retryJitter(retryJitter: Duration) = apply { this.retryJitter = retryJitter }

        fun minBackoff(minBackoff: Duration) = apply { this.minBackoff = minBackoff }

        /** Validates and builds; throws [IllegalArgumentException] on inconsistent timings. */
        fun build(): SimbaConfig {
            val renew = renewInterval ?: leaseDuration.dividedBy(3)
            return SimbaConfig(leaseDuration, renew, drift, margin, retryJitter ?: renew.dividedBy(2), minBackoff)
        }
    }

    companion object {
        const val MAX_DRIFT = 0.5
        private val DEFAULT_LEASE: Duration = Duration.ofSeconds(15)
        private const val DEFAULT_DRIFT = 0.01
        private val DEFAULT_MARGIN: Duration = Duration.ofMillis(50)
        private val DEFAULT_MIN_BACKOFF: Duration = Duration.ofMillis(100)

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
