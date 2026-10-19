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
import me.ahoo.simba.event.ElectionKind
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
 * [kinds] tells which API started each election (absent means [ElectionKind.ELECTION]). [works] names the elections
 * whose terms carry work (ADR 0010 contract 3): stopping or stepping down such a term
 * waits in [ElectionState.Handover] before releasing its lease. [groups] are the groups this client joins. A
 * non-empty [closing] holds the close requests waiting for the last handover.
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
    val works: Set<LeaseName> = emptySet(),
    val kinds: Map<LeaseName, ElectionKind> = emptyMap(),
    val groups: Map<String, GroupState> = emptyMap(),
    val closing: List<CompletableFuture<Void>> = emptyList(),
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
     * @param leaseShortened `SessionLeaseShortened` was published for this session.
     * @param renewEvery the keep-alive period in effect when it differs from the configured renew interval; also the
     *   timeout of every backend call, so a stuck call cannot outlast the retries the period leaves.
     */
    data class Active(
        val id: SessionId,
        val validUntil: Long,
        val suspended: Boolean,
        val keepAlive: Long?,
        val renewTimer: Long?,
        val deadlineTimer: Long,
        val abandoned: Map<LeaseName, Long>,
        val leaseShortened: Boolean = false,
        val renewEvery: Long? = null,
    ) : SessionState

    /** Closed; completes [done] when [request] (the session close) replies. */
    data class Closed(val request: Long?, val done: List<CompletableFuture<Void>>) : SessionState
}

@InternalSimbaApi
sealed interface ElectionState {
    /**
     * Contending: [attempt] is the acquire in flight, [retryTimer] the next attempt; both null means idle. [probe]
     * completes when the first answered attempt does not elect this node. [released] when a release hint arrived
     * while [attempt] was in flight: its reply may predate the release, so a lost attempt contends again at once.
     */
    data class Candidate(
        val attempt: Long?,
        val retryTimer: Long?,
        val probe: CompletableFuture<Void>? = null,
        val released: Boolean = false,
    ) : ElectionState

    /**
     * Holding term [term] (also the id-order of its adoption) with fencing token [token]; [working] while the work
     * bound to the term has not exited.
     */
    data class Leader(val term: Long, val token: Long, val working: Boolean = false) : ElectionState

    /** Not contending until [timer] fires. */
    data class Cooldown(val timer: Long) : ElectionState

    /**
     * Term [term] was revoked by a stop or step-down while its work was running: the lease, owned by [session] with
     * [token], is released once the work exits or [timer] fires at [deadline] (the session's local deadline when
     * the handover began), whichever comes first. Then [next] applies and [done] completes.
     */
    data class Handover(
        val term: Long,
        val token: Long,
        val session: SessionId,
        val deadline: Long,
        val timer: Long,
        val next: AfterHandover,
        val done: List<CompletableFuture<Void>>,
    ) : ElectionState
}

/** What an election does once its [ElectionState.Handover] ends. */
@InternalSimbaApi
sealed interface AfterHandover {
    /** The election was stopped: it is removed. */
    data object Stop : AfterHandover

    /**
     * Contend again at [at] (monotonic nanoseconds): the end of a cooldown, or a restart after a stop, carrying the
     * restart's [probe].
     */
    data class Resume(val at: Long, val probe: CompletableFuture<Void>? = null) : AfterHandover
}

/**
 * Membership of one group: [joined] is the session the group was joined with, [request] the join, members or leave
 * request in flight, [refreshTimer] the next refresh (or retry), and [session] / [members] the view published last.
 * [stale] when a membership hint arrived while [request] was in flight: the members are read again at once after it.
 * While [leaving], no view is published and the group is forgotten once a leave succeeded (sent after an in-flight
 * join answers, retried when it fails).
 */
@InternalSimbaApi
data class GroupState(
    val leaving: Boolean = false,
    val joined: SessionId? = null,
    val request: Long? = null,
    val refreshTimer: Long? = null,
    val session: SessionId? = null,
    val members: List<SessionId>? = null,
    val stale: Boolean = false,
)
