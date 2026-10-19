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

import me.ahoo.simba.RevokeReason
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Input of [step]. The futures are opaque tokens to the core: it only hands them back in [Effect.Complete].
 */
@InternalSimbaApi
sealed interface Event {
    /** Opens the first session. */
    data object Boot : Event

    /**
     * Starts contending for [name]. With [work], every term carries work that must exit (see [WorkExited]) before a
     * stop or step-down releases the lease. [probe] completes when the first answered attempt does not elect this
     * node (used by `tryAcquire`), or at once when the election is already started; it may never complete if the
     * election stops first. [kind] labels the election's events and snapshot.
     */
    data class Start(
        val name: LeaseName,
        val work: Boolean = false,
        val probe: CompletableFuture<Void>? = null,
        val kind: ElectionKind = ElectionKind.ELECTION,
    ) : Event

    /**
     * Stops the election (ADR 0010 contract 3): a held term is revoked first. Without work running, its lease is
     * released at once; otherwise only after [WorkExited] for the term or after the session's local deadline at the
     * time of the stop, whichever comes first. Then [done] completes.
     */
    data class Stop(val name: LeaseName, val done: CompletableFuture<Void>) : Event

    /** Gives up the held term like [Stop] and does not contend before [cooldown] nanoseconds from now. */
    data class StepDown(val name: LeaseName, val cooldown: Long, val done: CompletableFuture<Void>) : Event

    data class Close(val done: CompletableFuture<Void>) : Event

    data class Timer(val id: Long) : Event

    /** The single outcome of request [requestId]: its result, or [BackendResult.Failed] on error or timeout. */
    data class Reply(val requestId: Long, val result: BackendResult) : Event

    data class Hint(val hint: BackendHint) : Event

    /**
     * The work bound to [term] of [name] exited (ADR 0010 contract 3): a stopping or stepping-down term releases its
     * lease now, and a later stop of a still-held term releases at once.
     */
    data class WorkExited(val name: LeaseName, val term: Long) : Event

    /** Joins [group] with the current session and every later one, and publishes its members. */
    data class Join(val group: String) : Event

    /** Leaves [group]. */
    data class Leave(val group: String) : Event
}

/** Output of [step], executed in order by a shell. */
@InternalSimbaApi
sealed interface Effect {
    /** Runs [request] off the event loop; its outcome must come back as exactly one [Event.Reply]. */
    data class Call(val requestId: Long, val request: BackendRequest) : Effect

    /** Delivers [Event.Timer] once the ticker reaches [at]. Timers are never cancelled; stale ones are ignored. */
    data class Arm(val id: Long, val at: Long) : Effect

    /** The local deadline of [session] moved to [validUntil]. */
    data class Deadline(val session: SessionId, val validUntil: Long) : Effect

    /** Creates term [term] bound to [session]'s deadline and dispatches `onElected`. */
    data class Elected(val name: LeaseName, val term: Long, val token: Long, val session: SessionId) : Effect

    /** Revokes term [term] (before any later effect) and dispatches `onRevoked`. */
    data class Revoked(val name: LeaseName, val term: Long, val reason: RevokeReason) : Effect

    /** Completes [done]; with a [name], only after that election's callbacks dispatched so far ran. */
    data class Complete(val name: LeaseName?, val done: CompletableFuture<Void>) : Effect

    data class Publish(val event: SimbaEvent) : Effect

    /**
     * The view of [group] changed: its live [members] as seen by [session], or no session and no members while this
     * node has no live session in the group.
     */
    data class Members(val group: String, val session: SessionId?, val members: List<SessionId>) : Effect
}

@InternalSimbaApi
sealed interface BackendRequest {
    data class OpenSession(val lease: Duration) : BackendRequest

    data class KeepAlive(val session: SessionId, val lease: Duration, val held: Set<LeaseName>) : BackendRequest

    data class Acquire(val session: SessionId, val names: Set<LeaseName>) : BackendRequest

    data class Release(val session: SessionId, val leases: Map<LeaseName, Long>) : BackendRequest

    data class CloseSession(val session: SessionId) : BackendRequest

    data class Join(val session: SessionId, val group: String) : BackendRequest

    data class Leave(val session: SessionId, val group: String) : BackendRequest

    data class Members(val group: String) : BackendRequest
}

@InternalSimbaApi
sealed interface BackendResult {
    data class Opened(val grant: SessionGrant) : BackendResult

    data class KeptAlive(val result: KeepAliveResult) : BackendResult

    data class Acquired(val results: Map<LeaseName, AcquireResult>) : BackendResult

    data object Done : BackendResult

    data class Members(val members: List<SessionId>) : BackendResult

    data class Failed(val cause: Throwable) : BackendResult
}

/** Executes [request] against [backend]; exceptions become [BackendResult.Failed]. */
@InternalSimbaApi
fun LeaseBackend.execute(request: BackendRequest): BackendResult =
    try {
        when (request) {
            is BackendRequest.OpenSession -> BackendResult.Opened(openSession(request.lease))
            is BackendRequest.KeepAlive -> BackendResult.KeptAlive(
                keepAlive(request.session, request.lease, request.held)
            )
            is BackendRequest.Acquire -> BackendResult.Acquired(acquire(request.session, request.names))
            is BackendRequest.Release -> {
                release(request.session, request.leases)
                BackendResult.Done
            }

            is BackendRequest.CloseSession -> {
                closeSession(request.session)
                BackendResult.Done
            }

            is BackendRequest.Join -> {
                join(request.session, request.group)
                BackendResult.Done
            }

            is BackendRequest.Leave -> {
                leave(request.session, request.group)
                BackendResult.Done
            }

            is BackendRequest.Members -> BackendResult.Members(members(request.group))
        }
    } catch (error: Exception) {
        BackendResult.Failed(error)
    }
