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

    data class Start(val name: LeaseName) : Event

    /**
     * Stops the election. A held term is revoked, then its lease released, then [done] completes.
     *
     * TODO(ADR 0010 contract 3, phase 5): once work is bound to terms, release only after [WorkExited] for the term
     * or after the term's remaining time, whichever comes first; with no work bound, release at once as today.
     */
    data class Stop(val name: LeaseName, val done: CompletableFuture<Void>) : Event

    /** Gives up the held term like [Stop] (same TODO about waiting for work) and pauses for [cooldown] nanoseconds. */
    data class StepDown(val name: LeaseName, val cooldown: Long, val done: CompletableFuture<Void>) : Event

    data class Close(val done: CompletableFuture<Void>) : Event

    data class Timer(val id: Long) : Event

    /** The single outcome of request [requestId]: its result, or [BackendResult.Failed] on error or timeout. */
    data class Reply(val requestId: Long, val result: BackendResult) : Event

    data class Hint(val hint: BackendHint) : Event

    /**
     * Reserved for phase 5 (ADR 0010 contract 3): the work bound to [term] of [name] exited, so the lease of a
     * stopping or stepping-down term may be released. Ignored until work is bound to terms.
     */
    data class WorkExited(val name: LeaseName, val term: Long) : Event
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
}

@InternalSimbaApi
sealed interface BackendRequest {
    data class OpenSession(val lease: Duration) : BackendRequest

    data class KeepAlive(val session: SessionId, val lease: Duration, val held: Set<LeaseName>) : BackendRequest

    data class Acquire(val session: SessionId, val names: Set<LeaseName>) : BackendRequest

    data class Release(val session: SessionId, val leases: Map<LeaseName, Long>) : BackendRequest

    data class CloseSession(val session: SessionId) : BackendRequest
}

@InternalSimbaApi
sealed interface BackendResult {
    data class Opened(val grant: SessionGrant) : BackendResult

    data class KeptAlive(val result: KeepAliveResult) : BackendResult

    data class Acquired(val results: Map<LeaseName, AcquireResult>) : BackendResult

    data object Done : BackendResult

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
        }
    } catch (error: Exception) {
        BackendResult.Failed(error)
    }
