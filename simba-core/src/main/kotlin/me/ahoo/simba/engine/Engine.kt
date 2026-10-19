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
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
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
 * - a term is revoked before its lease is released; a term whose work is running keeps its lease until the work
 *   exited or the session's local deadline at the time of the stop passed, whichever comes first;
 * - keep-alive replies list the leases the session owns: a held lease missing from a reply sent after its adoption
 *   is revoked, and an owned lease the engine neither holds nor is acquiring is released.
 */
@InternalSimbaApi
fun step(state: EngineState, event: Event, now: Long): Pair<EngineState, List<Effect>> {
    val transition = Transition(state, now)
    transition.handle(event)
    return transition.result()
}

/** One step of the state machine. Kept in one class on purpose: every rule reads and writes the same state. */
@Suppress("LargeClass")
@OptIn(InternalSimbaApi::class)
private class Transition(state: EngineState, private val now: Long) {
    private val config = state.config
    private var random = state.random
    private var nextId = state.nextId
    private var session = state.session
    private val elections = LinkedHashMap(state.elections)
    private val pending = HashMap(state.pending)
    private val works = HashSet(state.works)
    private val kinds = HashMap(state.kinds)
    private val groups = LinkedHashMap(state.groups)
    private var closing = state.closing
    private val effects = ArrayList<Effect>()

    fun result(): Pair<EngineState, List<Effect>> =
        EngineState(config, random, nextId, session, elections, pending, works, kinds, groups, closing) to effects

    fun handle(event: Event) {
        when (event) {
            Event.Boot -> boot()
            is Event.Start -> start(event)
            is Event.Stop -> stop(event.name, event.done)
            is Event.StepDown -> stepDown(event.name, event.cooldown, event.done)
            is Event.Close -> close(event.done)
            is Event.Timer -> timer(event.id)
            is Event.Reply -> reply(event.requestId, event.result)
            is Event.Hint -> hint(event.hint)
            is Event.WorkExited -> workExited(event.name, event.term)
            is Event.Join -> join(event.group)
            is Event.Leave -> leave(event.group)
        }
    }

    // region commands

    private fun boot() {
        val current = session
        if (current is SessionState.Opening && current.request == null && current.retryTimer == null) {
            openSession()
        }
    }

    private fun start(event: Event.Start) {
        val name = event.name
        if (session is SessionState.Closed || closing.isNotEmpty()) {
            return
        }
        when (val election = elections[name]) {
            null -> elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null, probe = event.probe)
            is ElectionState.Handover -> if (election.next == AfterHandover.Stop) {
                elections[name] = election.copy(next = AfterHandover.Resume(now, event.probe))
            } else {
                alreadyStarted(event)
                return
            }

            else -> {
                alreadyStarted(event)
                return
            }
        }
        if (event.work) works += name else works -= name
        if (event.kind == ElectionKind.ELECTION) kinds -= name else kinds[name] = event.kind
        tryAcquire(name)
    }

    /** A start of a started election changes nothing; its probe reports at once that it did not elect. */
    private fun alreadyStarted(event: Event.Start) {
        event.probe?.let { effects += Effect.Complete(event.name, it) }
    }

    private fun stop(name: LeaseName, done: CompletableFuture<Void>) {
        when (val election = elections[name]) {
            is ElectionState.Leader -> {
                revoke(name, election, RevokeReason.STOPPED)
                if (election.working && handOver(name, election, AfterHandover.Stop, done)) {
                    return
                }
                releaseLease(name, election.token)
                remove(name)
            }

            is ElectionState.Handover -> {
                elections[name] = election.copy(next = AfterHandover.Stop, done = election.done + done)
                return
            }

            else -> remove(name)
        }
        effects += Effect.Complete(name, done)
    }

    private fun stepDown(name: LeaseName, cooldown: Long, done: CompletableFuture<Void>) {
        when (val election = elections[name]) {
            null -> Unit
            is ElectionState.Handover -> {
                val next = when (val current = election.next) {
                    AfterHandover.Stop -> current
                    is AfterHandover.Resume -> current.copy(at = later(current.at, now + cooldown))
                }
                elections[name] = election.copy(next = next, done = election.done + done)
                return
            }

            is ElectionState.Leader -> {
                revoke(name, election, RevokeReason.STEPPED_DOWN)
                if (election.working && handOver(name, election, AfterHandover.Resume(now + cooldown), done)) {
                    return
                }
                releaseLease(name, election.token)
                elections[name] = ElectionState.Cooldown(arm(now + cooldown))
            }

            else -> elections[name] = ElectionState.Cooldown(arm(now + cooldown))
        }
        effects += Effect.Complete(name, done)
    }

    /**
     * Keeps the lease of the revoked [leader] until its work exits or the session's local deadline passes; `false`
     * (release at once) without a live session, since nothing could keep the lease anyway.
     */
    private fun handOver(
        name: LeaseName,
        leader: ElectionState.Leader,
        next: AfterHandover,
        done: CompletableFuture<Void>?,
    ): Boolean {
        val current = session as? SessionState.Active ?: return false
        if (current.validUntil - now <= 0) {
            return false
        }
        elections[name] = ElectionState.Handover(
            term = leader.term,
            token = leader.token,
            session = current.id,
            deadline = current.validUntil,
            timer = arm(current.validUntil),
            next = next,
            done = listOfNotNull(done),
        )
        return true
    }

    private fun workExited(name: LeaseName, term: Long) {
        when (val election = elections[name]) {
            is ElectionState.Leader -> if (election.term == term) {
                elections[name] = election.copy(working = false)
            }

            is ElectionState.Handover -> if (election.term == term) {
                endHandover(name, election)
            }

            else -> Unit
        }
    }

    /** Releases the lease (if its session is still the current one), completes the waiters, then applies `next`. */
    private fun endHandover(name: LeaseName, handover: ElectionState.Handover) {
        val current = session
        if (current is SessionState.Active && current.id == handover.session) {
            releaseLease(name, handover.token)
        }
        handover.done.forEach { effects += Effect.Complete(name, it) }
        when (val next = handover.next) {
            AfterHandover.Stop -> remove(name)
            is AfterHandover.Resume -> if (now - next.at >= 0) {
                elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null, probe = next.probe)
                tryAcquire(name)
            } else {
                next.probe?.let { effects += Effect.Complete(name, it) }
                elections[name] = ElectionState.Cooldown(arm(next.at))
            }
        }
        closeIfDrained()
    }

    private fun remove(name: LeaseName) {
        elections.remove(name)
        works -= name
        kinds -= name
    }

    private fun close(done: CompletableFuture<Void>) {
        if (session is SessionState.Closed) {
            effects += Effect.Complete(null, done)
            return
        }
        for ((name, election) in elections.entries.toList()) {
            when (election) {
                is ElectionState.Leader -> {
                    revoke(name, election, RevokeReason.STOPPED)
                    if (!election.working || !handOver(name, election, AfterHandover.Stop, null)) {
                        remove(name)
                    }
                }

                is ElectionState.Handover -> elections[name] = election.copy(next = AfterHandover.Stop)
                else -> remove(name)
            }
        }
        groups.clear()
        closing = closing + done
        closeIfDrained()
    }

    /** Closes the session once a requested close has no handover left; until then held leases are released. */
    private fun closeIfDrained() {
        if (closing.isEmpty()) {
            return
        }
        if (elections.isNotEmpty()) {
            return
        }
        val waiting = closing
        closing = emptyList()
        val current = session
        effects += Effect.Publish(SimbaEvent.SessionClosed((current as? SessionState.Active)?.id))
        if (current is SessionState.Active) {
            session = SessionState.Closed(call(BackendRequest.CloseSession(current.id)), waiting)
        } else {
            session = SessionState.Closed(null, emptyList())
            waiting.forEach { effects += Effect.Complete(null, it) }
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
        if (groupTimer(id)) {
            return
        }
        val (name, election) = elections.entries.firstOrNull { (_, election) -> election.timer() == id } ?: return
        when (election) {
            is ElectionState.Handover -> endHandover(name, election)
            is ElectionState.Candidate -> {
                elections[name] = election.copy(attempt = null, retryTimer = null)
                tryAcquire(name)
            }

            else -> {
                elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
                tryAcquire(name)
            }
        }
    }

    /** Handles [id] when it is a group's refresh or retry timer; `false` when it is not one. */
    private fun groupTimer(id: Long): Boolean {
        val (group, state) = groups.entries.firstOrNull { (_, group) -> group.refreshTimer == id } ?: return false
        val current = session as? SessionState.Active ?: return true
        groups[group] = state.copy(refreshTimer = null)
        when {
            state.leaving -> sendLeave(group)
            state.joined == current.id -> refreshMembers(group)
            else -> joinGroup(group)
        }
        return true
    }

    private fun ElectionState.timer(): Long? =
        when (this) {
            is ElectionState.Candidate -> retryTimer
            is ElectionState.Cooldown -> timer
            is ElectionState.Handover -> timer
            is ElectionState.Leader -> null
        }

    private fun keepAlive(current: SessionState.Active) {
        val held = elections.filterValues {
            it is ElectionState.Leader || it is ElectionState.Handover && it.session == current.id
        }.keys
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
            is BackendRequest.Release -> onReleased(request, result)
            is BackendRequest.CloseSession -> onClosed(requestId)
            is BackendRequest.Join -> onJoined(requestId, request, result)
            is BackendRequest.Members -> onMembers(requestId, request, result)
            is BackendRequest.Leave -> onLeft(requestId, request, result)
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
        groups.keys.toList().forEach(::joinGroup)
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
        // Once the local deadline passed, the session's terms ended for good: a late renewal must not revive them.
        if (now - current.validUntil >= 0) {
            expire(current, RevokeReason.DEADLINE_PASSED)
            return
        }
        val validUntil = later(current.validUntil, sentAt + config.localValidity(alive.remaining))
        session = current.copy(
            validUntil = validUntil,
            suspended = false,
            keepAlive = null,
            renewTimer = arm(later(now, sentAt + config.renewInterval)),
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
        val orphans = owned.filter { (name, token) -> !holds(name, token) && name !in acquiring }
        if (orphans.isNotEmpty()) {
            orphans.forEach { (name, token) -> abandon(name, token) }
            call(BackendRequest.Release(current.id, orphans))
        }
        forget(current.abandoned.keys.filter { it !in owned })
    }

    /** `true` when [name] is held with [token]: by its term, or by a handover waiting for the term's work. */
    private fun holds(name: LeaseName, token: Long): Boolean =
        when (val election = elections[name]) {
            is ElectionState.Leader -> election.token == token
            is ElectionState.Handover -> election.token == token
            else -> false
        }

    /** A confirmed release frees its token at the backend, so the name no longer needs to be remembered. */
    private fun onReleased(request: BackendRequest.Release, result: BackendResult) {
        val current = session as? SessionState.Active ?: return
        if (result == BackendResult.Done && current.id == request.session) {
            forget(request.leases.keys)
        }
    }

    /**
     * Drops [names] from the abandoned tokens, except names with a request in flight: once the backend no longer
     * holds an abandoned token for this session and nothing in flight can answer it, any later grant carries a
     * fresh, higher token. Keeps the state bounded by the names in use, not every name ever used.
     */
    private fun forget(names: Collection<LeaseName>) {
        val current = session as SessionState.Active
        if (names.isEmpty() || current.abandoned.isEmpty()) {
            return
        }
        val busy = pending.values.asSequence()
            .map { it.request }
            .flatMap {
                when {
                    it is BackendRequest.Acquire && it.session == current.id -> it.names.asSequence()
                    it is BackendRequest.Release && it.session == current.id -> it.leases.keys.asSequence()
                    else -> emptySequence()
                }
            }
            .toSet()
        val forgotten = names.filter { it !in busy }
        if (forgotten.isNotEmpty()) {
            session = current.copy(abandoned = current.abandoned - forgotten.toSet())
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
                    lost(name, again = (elections[name] as ElectionState.Candidate).released)
                }

                null -> if (isAttempt(name, requestId)) {
                    val cause = (result as? BackendResult.Failed)?.cause
                        ?: IllegalStateException("No acquire result for [$name].")
                    effects += Effect.Publish(SimbaEvent.AcquireFailed(name, cause))
                    lost(name)
                }
            }
        }
    }

    private fun onGranted(requestId: Long, name: LeaseName, token: Long) {
        val current = session as SessionState.Active
        if (holds(name, token)) {
            return
        }
        val attempt = isAttempt(name, requestId)
        val adoptable = attempt && now - current.validUntil < 0 && token > (current.abandoned[name] ?: 0)
        if (adoptable) {
            val leader = ElectionState.Leader(term = newId(), token = token, working = name in works)
            elections[name] = leader
            effects += Effect.Elected(name, leader.term, token, current.id)
            effects += Effect.Publish(SimbaEvent.Elected(name, token, kindOf(name)))
            return
        }
        releaseLease(name, token)
        if (attempt) {
            lost(name)
        }
    }

    /**
     * The current attempt of [name] did not elect this node: reports the probe once and retries later, or [again] at
     * once.
     */
    private fun lost(name: LeaseName, again: Boolean = false) {
        (elections[name] as? ElectionState.Candidate)?.probe?.let { effects += Effect.Complete(name, it) }
        if (again) {
            elections[name] = ElectionState.Candidate(attempt = null, retryTimer = null)
            tryAcquire(name)
        } else {
            retryLater(name)
        }
    }

    private fun isAttempt(name: LeaseName, requestId: Long): Boolean =
        (elections[name] as? ElectionState.Candidate)?.attempt == requestId

    private fun onClosed(requestId: Long) {
        val current = session as? SessionState.Closed ?: return
        if (current.request == requestId) {
            current.done.forEach { effects += Effect.Complete(null, it) }
            session = SessionState.Closed(null, emptyList())
        }
    }

    // endregion

    // region groups

    private fun join(group: String) {
        if (session is SessionState.Closed || closing.isNotEmpty()) {
            return
        }
        val state = groups[group]
        if (state == null) {
            groups[group] = GroupState()
            joinGroup(group)
        } else if (state.leaving) {
            // Joined again while leaving: the pending leave still completes, then the group is joined anew.
            groups[group] = state.copy(leaving = false)
        }
    }

    /**
     * Leaves [group]: after the reply when a join is in flight (it may have succeeded), otherwise at once (also after
     * a failed join, which may have succeeded too), and not at all without a session (the membership, if any, ends
     * with its session).
     */
    private fun leave(group: String) {
        val state = groups[group]?.takeUnless { it.leaving } ?: return
        if (session !is SessionState.Active) {
            groups.remove(group)
            return
        }
        groups[group] = state.copy(leaving = true)
        if (state.request?.let { pending[it]?.request } !is BackendRequest.Join) {
            sendLeave(group)
        }
    }

    private fun sendLeave(group: String) {
        val current = session as? SessionState.Active ?: return
        val state = groups[group] ?: return
        groups[group] = state.copy(request = call(BackendRequest.Leave(current.id, group)), refreshTimer = null)
    }

    /** A leave that succeeded forgets the group, or joins it again when it was joined meanwhile; a failure retries. */
    private fun onLeft(requestId: Long, request: BackendRequest.Leave, result: BackendResult) {
        val state = groups[request.group]?.takeIf { it.request == requestId } ?: return
        when {
            result == BackendResult.Done ||
                result is BackendResult.Failed && result.cause is SessionExpiredException ->
                if (state.leaving) {
                    groups.remove(request.group)
                } else {
                    groups[request.group] = state.copy(joined = null, request = null)
                    joinGroup(request.group)
                }

            else -> groups[request.group] = state.copy(request = null, refreshTimer = arm(now + retryDelay()))
        }
    }

    /** Joins [group] with the current session; waits for the next session when none is live. */
    private fun joinGroup(group: String) {
        val current = session as? SessionState.Active ?: return
        val state = groups[group] ?: return
        groups[group] = state.copy(
            joined = null,
            request = call(BackendRequest.Join(current.id, group)),
            refreshTimer = null,
        )
    }

    private fun refreshMembers(group: String) {
        val state = groups[group] ?: return
        if (state.request == null) {
            groups[group] =
                state.copy(request = call(BackendRequest.Members(group)), refreshTimer = null, stale = false)
        }
    }

    private fun onJoined(requestId: Long, request: BackendRequest.Join, result: BackendResult) {
        val state = groups[request.group]?.takeIf { it.request == requestId } ?: return
        val current = session as? SessionState.Active
        if (current?.id != request.session) {
            return
        }
        if (state.leaving && !(result is BackendResult.Failed && result.cause is SessionExpiredException)) {
            // Left while joining: the join may have succeeded even when its reply failed, so leave either way.
            groups[request.group] = state.copy(joined = current.id, request = null)
            sendLeave(request.group)
            return
        }
        if (result is BackendResult.Failed && result.cause is SessionExpiredException) {
            expire(current, RevokeReason.SESSION_EXPIRED)
            return
        }
        if (result != BackendResult.Done) {
            groups[request.group] = state.copy(request = null, refreshTimer = arm(now + retryDelay()))
            return
        }
        groups[request.group] = state.copy(joined = current.id, request = null)
        refreshMembers(request.group)
    }

    /**
     * Publishes the view when it changed, then refreshes again after one renew interval, or at once when a hint made
     * the reply [GroupState.stale].
     */
    private fun onMembers(requestId: Long, request: BackendRequest.Members, result: BackendResult) {
        val state = groups[request.group]?.takeIf { it.request == requestId } ?: return
        if (result !is BackendResult.Members) {
            groups[request.group] = state.copy(request = null, refreshTimer = arm(now + retryDelay()))
            return
        }
        val viewer = state.joined
        if (state.stale) {
            groups[request.group] = publishView(request.group, state.copy(request = null), viewer, result.members)
            refreshMembers(request.group)
            return
        }
        val next = state.copy(request = null, refreshTimer = arm(now + config.renewInterval))
        groups[request.group] = publishView(request.group, next, viewer, result.members)
    }

    private fun publishView(group: String, state: GroupState, viewer: SessionId?, members: List<SessionId>): GroupState {
        if (state.session == viewer && state.members == members) {
            return state
        }
        effects += Effect.Members(group, viewer, members)
        return state.copy(session = viewer, members = members)
    }

    private fun groupHint(group: String) {
        val state = groups[group] ?: return
        if (!state.leaving && state.joined != null && state.joined == (session as? SessionState.Active)?.id) {
            if (state.request == null) {
                refreshMembers(group)
            } else {
                groups[group] = state.copy(stale = true)
            }
        }
    }

    // endregion

    private fun hint(hint: BackendHint) {
        if (hint is BackendHint.MembersChanged) {
            groupHint(hint.group)
            return
        }
        if (hint !is BackendHint.Released) {
            return
        }
        val election = elections[hint.name] as? ElectionState.Candidate ?: return
        if (election.attempt == null) {
            elections[hint.name] = election.copy(retryTimer = null)
            tryAcquire(hint.name)
        } else {
            elections[hint.name] = election.copy(released = true)
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

                is ElectionState.Candidate -> elections[name] = election.copy(attempt = null, retryTimer = null)
                is ElectionState.Cooldown, is ElectionState.Handover -> Unit
            }
        }
        for ((group, state) in groups.entries.toList()) {
            if (state.leaving) {
                // The membership ends with the session.
                groups.remove(group)
            } else {
                val reset = GroupState(session = state.session, members = state.members)
                groups[group] = publishView(group, reset, null, emptyList())
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
        elections[name] = election.copy(attempt = request, retryTimer = null, released = false)
    }

    /** Retries later; a reported probe is not reported again. */
    private fun retryLater(name: LeaseName) {
        elections[name] = ElectionState.Candidate(attempt = null, retryTimer = arm(now + retryDelay()))
    }

    private fun revoke(name: LeaseName, leader: ElectionState.Leader, reason: RevokeReason) {
        effects += Effect.Revoked(name, leader.term, reason)
        effects += Effect.Publish(SimbaEvent.Revoked(name, leader.token, reason, kindOf(name)))
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

    private fun kindOf(name: LeaseName): ElectionKind = kinds[name] ?: ElectionKind.ELECTION

    /** The later of two monotonic instants, compared by difference so that a wrapping clock stays ordered. */
    private fun later(a: Long, b: Long): Long = if (a - b >= 0) a else b

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
