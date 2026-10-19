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
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * The engine: a pure function of the current [state], one [event] and the monotonic time [now] (nanoseconds).
 *
 * No threads, no clock, no I/O: everything the engine wants done is returned as [Effect]s, which a shell executes
 * in order. Rules that make it safe under arbitrary delays, losses and pauses:
 * - a term is adopted only from the reply to the election's current attempt, while the session's local deadline
 *   has not passed, and only with a token above every token this session released;
 * - every other `Granted` is released with its token (releases are token-qualified, so a late one is harmless);
 * - a term is revoked before its lease is released;
 * - keep-alive replies list the leases the session owns: a held lease missing from a reply sent after its adoption
 *   is revoked, and an owned lease the engine neither holds nor is acquiring is released.
 */
@InternalSimbaApi
fun step(state: EngineState, event: Event, now: Long): Pair<EngineState, List<Effect>> {
    val transition = Transition(state, now)
    transition.handle(event)
    return transition.result()
}

@OptIn(InternalSimbaApi::class)
private class Transition(state: EngineState, private val now: Long) {
    private val config = state.config
    private var random = state.random
    private var nextId = state.nextId
    private var session = state.session
    private val elections = LinkedHashMap(state.elections)
    private val pending = HashMap(state.pending)
    private val effects = ArrayList<Effect>()

    fun result(): Pair<EngineState, List<Effect>> =
        EngineState(config, random, nextId, session, elections, pending) to effects

    fun handle(event: Event) {
        when (event) {
            Event.Boot -> boot()
            is Event.Start -> start(event.name)
            is Event.Stop -> stop(event.name, event.done)
            is Event.StepDown -> stepDown(event.name, event.cooldown, event.done)
            is Event.Close -> close(event.done)
            is Event.Timer -> timer(event.id)
            is Event.Reply -> reply(event.requestId, event.result)
            is Event.Hint -> hint(event.hint)
        }
    }

    // region commands

    private fun boot() {
        val current = session
        if (current is SessionState.Opening && current.request == null && current.retryTimer == null) {
            openSession()
        }
    }

    private fun start(name: LeaseName) {
        if (session is SessionState.Closed || name in elections) {
            return
        }
        elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
        tryAcquire(name)
    }

    private fun stop(name: LeaseName, done: CompletableFuture<Void>) {
        val election = elections.remove(name)
        if (election is ElectionState.Leader) {
            revoke(name, election, RevokeReason.STOPPED)
            releaseLease(name, election.token)
        }
        effects += Effect.Complete(name, done)
    }

    private fun stepDown(name: LeaseName, cooldown: Long, done: CompletableFuture<Void>) {
        val election = elections[name]
        if (election != null) {
            if (election is ElectionState.Leader) {
                revoke(name, election, RevokeReason.STEPPED_DOWN)
                releaseLease(name, election.token)
            }
            elections[name] = ElectionState.Cooldown(arm(now + cooldown))
        }
        effects += Effect.Complete(name, done)
    }

    private fun close(done: CompletableFuture<Void>) {
        val current = session
        if (current is SessionState.Closed) {
            effects += Effect.Complete(null, done)
            return
        }
        for ((name, election) in elections) {
            if (election is ElectionState.Leader) {
                revoke(name, election, RevokeReason.STOPPED)
            }
        }
        elections.clear()
        if (current is SessionState.Active) {
            session = SessionState.Closed(call(BackendRequest.CloseSession(current.id)), done)
        } else {
            session = SessionState.Closed(null, null)
            effects += Effect.Complete(null, done)
        }
    }

    // endregion

    // region timers

    private fun timer(id: Long) {
        when (val current = session) {
            is SessionState.Closed -> return
            is SessionState.Opening -> if (current.retryTimer == id) {
                openSession()
                return
            }

            is SessionState.Active -> {
                if (current.renewTimer == id) {
                    keepAlive(current)
                    return
                }
                if (current.deadlineTimer == id) {
                    deadline(current)
                    return
                }
            }
        }
        val name = elections.entries.firstOrNull { (_, election) -> election.timer() == id }?.key ?: return
        elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
        tryAcquire(name)
    }

    private fun ElectionState.timer(): Long? =
        when (this) {
            is ElectionState.Candidate -> retryTimer
            is ElectionState.Cooldown -> timer
            is ElectionState.Leader -> null
        }

    private fun keepAlive(current: SessionState.Active) {
        val held = elections.filterValues { it is ElectionState.Leader }.keys
        val request = call(BackendRequest.KeepAlive(current.id, config.leaseDuration, held))
        session = current.copy(keepAlive = request, renewTimer = null)
    }

    private fun deadline(current: SessionState.Active) {
        if (now - current.validUntil >= 0) {
            expire(current, RevokeReason.DEADLINE_PASSED)
        } else {
            session = current.copy(deadlineTimer = arm(current.validUntil))
        }
    }

    // endregion

    // region replies

    private fun reply(requestId: Long, result: BackendResult) {
        val sent = pending.remove(requestId) ?: return
        when (val request = sent.request) {
            is BackendRequest.OpenSession -> onOpened(requestId, sent.sentAt, result)
            is BackendRequest.KeepAlive -> onKeepAlive(requestId, sent.sentAt, result)
            is BackendRequest.Acquire -> onAcquire(requestId, request, result)
            is BackendRequest.Release -> Unit
            is BackendRequest.CloseSession -> onClosed(requestId)
        }
    }

    private fun onOpened(requestId: Long, sentAt: Long, result: BackendResult) {
        val current = session
        if (current !is SessionState.Opening || current.request != requestId) {
            if (result is BackendResult.Opened) {
                call(BackendRequest.CloseSession(result.grant.id))
            }
            return
        }
        if (result !is BackendResult.Opened) {
            session = SessionState.Opening(request = null, retryTimer = arm(now + retryDelay()))
            return
        }
        val grant = result.grant
        val validUntil = sentAt + config.localValidity(grant.remaining)
        if (validUntil - now <= 0) {
            call(BackendRequest.CloseSession(grant.id))
            session = SessionState.Opening(request = null, retryTimer = arm(now + retryDelay()))
            return
        }
        session = SessionState.Active(
            id = grant.id,
            validUntil = validUntil,
            suspended = false,
            keepAlive = null,
            renewTimer = arm(now + config.renewInterval),
            deadlineTimer = arm(validUntil),
            abandoned = emptyMap(),
        )
        effects += Effect.Deadline(grant.id, validUntil)
        effects += Effect.Publish(SimbaEvent.SessionOpened(grant.id))
        elections.keys.toList().forEach(::tryAcquire)
    }

    private fun onKeepAlive(requestId: Long, sentAt: Long, result: BackendResult) {
        val current = session as? SessionState.Active ?: return
        if (current.keepAlive != requestId) {
            return
        }
        when (result) {
            is BackendResult.KeptAlive -> when (val alive = result.result) {
                is KeepAliveResult.Live -> renewed(current, requestId, sentAt, alive)
                KeepAliveResult.Expired -> expire(current, RevokeReason.SESSION_EXPIRED)
            }

            is BackendResult.Failed -> if (result.cause is SessionExpiredException) {
                expire(current, RevokeReason.SESSION_EXPIRED)
            } else {
                suspend(current, result.cause)
            }

            else -> suspend(current, IllegalStateException("Unexpected keep-alive result: $result"))
        }
    }

    private fun renewed(current: SessionState.Active, requestId: Long, sentAt: Long, alive: KeepAliveResult.Live) {
        val validUntil = maxOf(current.validUntil, sentAt + config.localValidity(alive.remaining))
        if (validUntil - now <= 0) {
            expire(current, RevokeReason.DEADLINE_PASSED)
            return
        }
        session = current.copy(
            validUntil = validUntil,
            suspended = false,
            keepAlive = null,
            renewTimer = arm(maxOf(now, sentAt + config.renewInterval)),
        )
        if (validUntil != current.validUntil) {
            effects += Effect.Deadline(current.id, validUntil)
        }
        effects += Effect.Publish(SimbaEvent.SessionRenewed(current.id, Duration.ofNanos(now - sentAt)))
        reconcile(requestId, alive.owned)
        if (current.suspended) {
            elections.keys.toList().forEach(::tryAcquire)
        }
    }

    /**
     * Compares the backend's view of the leases owned by the session, as of keep-alive [requestId], with the local
     * one: revokes terms adopted before that request whose lease is gone, and releases owned leases nobody here
     * holds or is acquiring (an acquire that succeeded after its reply was lost or timed out).
     */
    private fun reconcile(requestId: Long, owned: Map<LeaseName, Long>) {
        for ((name, election) in elections.entries.toList()) {
            if (election is ElectionState.Leader && election.term < requestId && owned[name] != election.token) {
                revoke(name, election, RevokeReason.TAKEN_OVER)
                elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
                retryLater(name)
            }
        }
        val current = session as SessionState.Active
        val acquiring = pending.values.asSequence()
            .map { it.request }
            .filterIsInstance<BackendRequest.Acquire>()
            .filter { it.session == current.id }
            .flatMap { it.names }
            .toSet()
        val orphans = owned.filter { (name, token) ->
            val election = elections[name]
            !(election is ElectionState.Leader && election.token == token) && name !in acquiring
        }
        if (orphans.isNotEmpty()) {
            orphans.forEach { (name, token) -> abandon(name, token) }
            call(BackendRequest.Release(current.id, orphans))
        }
    }

    private fun suspend(current: SessionState.Active, cause: Throwable) {
        val left = current.validUntil - now
        if (left <= 0) {
            expire(current, RevokeReason.DEADLINE_PASSED)
            return
        }
        val backoff = (left / 2).coerceAtMost(config.renewInterval)
            .coerceAtLeast(minOf(config.minBackoff, config.renewInterval))
        session = current.copy(suspended = true, keepAlive = null, renewTimer = arm(now + backoff))
        if (!current.suspended) {
            effects += Effect.Publish(SimbaEvent.SessionSuspended(current.id, cause))
        }
    }

    private fun onAcquire(requestId: Long, request: BackendRequest.Acquire, result: BackendResult) {
        val current = session as? SessionState.Active ?: return
        if (current.id != request.session) {
            return
        }
        if (result is BackendResult.Failed && result.cause is SessionExpiredException) {
            expire(current, RevokeReason.SESSION_EXPIRED)
            return
        }
        for (name in request.names) {
            val outcome = (result as? BackendResult.Acquired)?.results?.get(name)
            when (outcome) {
                is AcquireResult.Granted -> onGranted(requestId, name, outcome.token)
                is AcquireResult.HeldBy -> if (isAttempt(name, requestId)) {
                    retryLater(name)
                }

                null -> if (isAttempt(name, requestId)) {
                    val cause = (result as? BackendResult.Failed)?.cause
                        ?: IllegalStateException("No acquire result for [$name].")
                    effects += Effect.Publish(SimbaEvent.AcquireFailed(name, cause))
                    retryLater(name)
                }
            }
        }
    }

    private fun onGranted(requestId: Long, name: LeaseName, token: Long) {
        val current = session as SessionState.Active
        val election = elections[name]
        if (election is ElectionState.Leader && election.token == token) {
            return
        }
        val attempt = isAttempt(name, requestId)
        val adoptable = attempt && now - current.validUntil < 0 && token > (current.abandoned[name] ?: 0)
        if (adoptable) {
            val leader = ElectionState.Leader(term = newId(), token = token)
            elections[name] = leader
            effects += Effect.Elected(name, leader.term, token, current.id)
            effects += Effect.Publish(SimbaEvent.Elected(name, token))
            return
        }
        releaseLease(name, token)
        if (attempt) {
            retryLater(name)
        }
    }

    private fun isAttempt(name: LeaseName, requestId: Long): Boolean =
        (elections[name] as? ElectionState.Candidate)?.attempt == requestId

    private fun onClosed(requestId: Long) {
        val current = session as? SessionState.Closed ?: return
        if (current.request == requestId) {
            current.done?.let { effects += Effect.Complete(null, it) }
            session = SessionState.Closed(null, null)
        }
    }

    // endregion

    private fun hint(hint: BackendHint) {
        if (hint !is BackendHint.Released) {
            return
        }
        val election = elections[hint.name] as? ElectionState.Candidate ?: return
        if (election.attempt == null) {
            elections[hint.name] = ElectionState.Candidate(attempt = null, retryTimer = null)
            tryAcquire(hint.name)
        }
    }

    // region helpers

    private fun openSession() {
        session = SessionState.Opening(request = call(BackendRequest.OpenSession(config.leaseDuration)), retryTimer = null)
    }

    /** Ends the session: revokes every term, forgets in-flight attempts, and opens a new session. */
    private fun expire(current: SessionState.Active, reason: RevokeReason) {
        for ((name, election) in elections.entries.toList()) {
            when (election) {
                is ElectionState.Leader -> {
                    revoke(name, election, reason)
                    elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
                }

                is ElectionState.Candidate -> elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
                is ElectionState.Cooldown -> Unit
            }
        }
        effects += Effect.Publish(SimbaEvent.SessionExpired(current.id, reason))
        if (reason == RevokeReason.DEADLINE_PASSED) {
            call(BackendRequest.CloseSession(current.id))
        }
        openSession()
    }

    private fun tryAcquire(name: LeaseName) {
        val current = session as? SessionState.Active ?: return
        val election = elections[name] as? ElectionState.Candidate ?: return
        if (current.suspended || election.attempt != null) {
            return
        }
        val request = call(BackendRequest.Acquire(current.id, setOf(name)))
        elections[name] = ElectionState.Candidate(attempt = request, retryTimer = null)
    }

    private fun retryLater(name: LeaseName) {
        elections[name] = ElectionState.Candidate(attempt = null, retryTimer = arm(now + retryDelay()))
    }

    private fun revoke(name: LeaseName, leader: ElectionState.Leader, reason: RevokeReason) {
        effects += Effect.Revoked(name, leader.term, reason)
        effects += Effect.Publish(SimbaEvent.Revoked(name, leader.token, reason))
    }

    private fun releaseLease(name: LeaseName, token: Long) {
        val current = session as? SessionState.Active ?: return
        abandon(name, token)
        call(BackendRequest.Release(current.id, mapOf(name to token)))
    }

    private fun abandon(name: LeaseName, token: Long) {
        val current = session as SessionState.Active
        if (token > (current.abandoned[name] ?: 0)) {
            session = current.copy(abandoned = current.abandoned + (name to token))
        }
    }

    private fun call(request: BackendRequest): Long {
        val id = newId()
        pending[id] = Pending(request, now)
        effects += Effect.Call(id, request)
        return id
    }

    private fun arm(at: Long): Long {
        val id = newId()
        effects += Effect.Arm(id, at)
        return id
    }

    private fun newId(): Long = nextId++

    private fun retryDelay(): Long = config.retryBase + nextRandom(config.retryJitter)

    /** SplitMix64, so that the jitter sequence is part of the state and replays exactly. */
    private fun nextRandom(bound: Long): Long {
        if (bound <= 0) {
            return 0
        }
        random += GOLDEN_GAMMA
        var z = random
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return Math.floorMod(z xor (z ushr 31), bound)
    }

    // endregion

    private companion object {
        const val GOLDEN_GAMMA = -7046029254386353131L
        const val MIX_1 = -4658895280553007687L
        const val MIX_2 = -7723592293110705685L
    }
}
