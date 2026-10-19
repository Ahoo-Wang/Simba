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

package me.ahoo.simba.engine

import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** [SimbaConfig] in the engine's unit, nanoseconds. */
@InternalSimbaApi
data class EngineConfig(
    val leaseDuration: Duration,
    val renewInterval: Long,
    val drift: Double,
    val margin: Long,
    val retryBase: Long,
    val retryJitter: Long,
    val minBackoff: Long,
) {
    /** Local validity granted by a backend-reported [remaining]: `remaining × (1 − drift) − margin`. */
    fun localValidity(remaining: Duration): Long = (remaining.toNanos() * (1.0 - drift)).toLong() - margin

    companion object {
        fun of(config: SimbaConfig): EngineConfig =
            EngineConfig(
                leaseDuration = config.leaseDuration,
                renewInterval = config.renewInterval.toNanos(),
                drift = config.drift,
                margin = config.margin.toNanos(),
                retryBase = config.renewInterval.toNanos() / 2,
                retryJitter = config.retryJitter.toNanos(),
                minBackoff = config.minBackoff.toNanos(),
            )
    }
}

/**
 * The whole engine state. Immutable; only [step] produces a new one.
 *
 * Ids of requests, timers and terms come from one counter ([nextId]), so comparing two ids tells which was created
 * first.
 */
@InternalSimbaApi
data class EngineState(
    val config: EngineConfig,
    val random: Long,
    val nextId: Long,
    val session: SessionState,
    val elections: Map<LeaseName, ElectionState>,
    val pending: Map<Long, Pending>,
) {
    companion object {
        fun initial(config: EngineConfig, seed: Long): EngineState =
            EngineState(
                config = config,
                random = seed,
                nextId = 1,
                session = SessionState.Opening(request = null, retryTimer = null),
                elections = emptyMap(),
                pending = emptyMap(),
            )
    }
}

/** A backend request in flight, sent at [sentAt] (monotonic nanoseconds). */
@InternalSimbaApi
data class Pending(val request: BackendRequest, val sentAt: Long)

@InternalSimbaApi
sealed interface SessionState {
    /** Waiting for [request], or for [retryTimer] after a failed open. */
    data class Opening(val request: Long?, val retryTimer: Long?) : SessionState

    /**
     * @param validUntil local deadline (monotonic nanoseconds); never moves backwards.
     * @param suspended the last keep-alive failed.
     * @param keepAlive the keep-alive in flight, if any; otherwise [renewTimer] schedules the next one.
     * @param abandoned per name, the highest token this session released or refused; never adopted again.
     */
    data class Active(
        val id: SessionId,
        val validUntil: Long,
        val suspended: Boolean,
        val keepAlive: Long?,
        val renewTimer: Long?,
        val deadlineTimer: Long,
        val abandoned: Map<LeaseName, Long>,
    ) : SessionState

    /** Closed; completes [done] when [request] (the session close) replies. */
    data class Closed(val request: Long?, val done: CompletableFuture<Void>?) : SessionState
}

@InternalSimbaApi
sealed interface ElectionState {
    /** Contending: [attempt] is the acquire in flight, [retryTimer] the next attempt; both null means idle. */
    data class Candidate(val attempt: Long?, val retryTimer: Long?) : ElectionState

    /** Holding term [term] (also the id-order of its adoption) with fencing token [token]. */
    data class Leader(val term: Long, val token: Long) : ElectionState

    /** Not contending until [timer] fires. */
    data class Cooldown(val timer: Long) : ElectionState
}
