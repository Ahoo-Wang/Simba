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
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Table-style tests of the pure core: feed events, assert effects. No threads, no clock. */
class EngineTest {
    private val config = EngineConfig.of(
        SimbaConfig(
            leaseDuration = Duration.ofSeconds(3),
            renewInterval = Duration.ofSeconds(1),
            drift = 0.01,
            margin = Duration.ofMillis(20),
            retryJitter = Duration.ZERO,
        )
    )
    private val name = LeaseName("orders")
    private val session = SessionId("s1")

    private var state = EngineState.initial(config, 7)
    private var effects: List<Effect> = emptyList()

    private fun on(event: Event, now: Long = 0): List<Effect> {
        val (next, produced) = step(state, event, now)
        state = next
        effects = produced
        return produced
    }

    private inline fun <reified R : BackendRequest> call(): Effect.Call =
        effects.filterIsInstance<Effect.Call>().single { it.request is R }

    private fun open(now: Long = 0) {
        on(Event.Boot, now)
        on(Event.Reply(call<BackendRequest.OpenSession>().requestId, opened()), now)
    }

    private fun opened() = BackendResult.Opened(SessionGrant(session, Duration.ofSeconds(3)))

    private fun elect(token: Long = 1, now: Long = 0) {
        on(Event.Start(name), now)
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(token)), now)
    }

    private fun granted(token: Long) = BackendResult.Acquired(mapOf(name to AcquireResult.Granted(token)))

    private val active get() = state.session as SessionState.Active

    @Test
    fun `boot opens a session whose deadline counts from when the request was sent`() {
        on(Event.Boot, now = 0)
        val open = call<BackendRequest.OpenSession>()
        on(Event.Reply(open.requestId, opened()), now = MILLIS * 500)

        active.validUntil.assert().isEqualTo(2_970 * MILLIS - 20 * MILLIS)
        effects.assert().contains(Effect.Deadline(session, active.validUntil))
    }

    @Test
    fun `a candidate acquires once the session is live and adopts its own grant`() {
        on(Event.Start(name))
        effects.filterIsInstance<Effect.Call>().assert().isEmpty()
        open()
        val acquire = call<BackendRequest.Acquire>()
        on(Event.Reply(acquire.requestId, granted(5)))

        val elected = effects.filterIsInstance<Effect.Elected>().single()
        elected.token.assert().isEqualTo(5)
        state.elections[name].assert().isEqualTo(ElectionState.Leader(elected.term, 5))
    }

    @Test
    fun `a grant arriving after the local deadline is released, not adopted`() {
        open()
        on(Event.Start(name))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(3)), now = active.validUntil)

        effects.filterIsInstance<Effect.Elected>().assert().isEmpty()
        call<BackendRequest.Release>().request.assert().isEqualTo(BackendRequest.Release(session, mapOf(name to 3L)))
    }

    @Test
    fun `stop revokes before releasing and completes after the revocation`() {
        open()
        elect(token = 2)
        val done = CompletableFuture<Void>()
        on(Event.Stop(name, done))

        val revoked = effects.indexOfFirst { it is Effect.Revoked }
        val release = effects.indexOfFirst { it is Effect.Call && it.request is BackendRequest.Release }
        val complete = effects.indexOf(Effect.Complete(name, done))
        (revoked in 0 until release).assert().isTrue()
        (release < complete).assert().isTrue()
        (effects[revoked] as Effect.Revoked).reason.assert().isEqualTo(RevokeReason.STOPPED)
    }

    @Test
    fun `a grant arriving after stop is released with its token`() {
        open()
        on(Event.Start(name))
        val acquire = call<BackendRequest.Acquire>()
        on(Event.Stop(name, CompletableFuture()))
        on(Event.Reply(acquire.requestId, granted(9)))

        effects.filterIsInstance<Effect.Elected>().assert().isEmpty()
        call<BackendRequest.Release>().request.assert().isEqualTo(BackendRequest.Release(session, mapOf(name to 9L)))
    }

    @Test
    fun `a released token is never adopted again by the same session`() {
        open()
        elect(token = 4)
        on(Event.StepDown(name, cooldown = 0, CompletableFuture()))
        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(4)))

        effects.filterIsInstance<Effect.Elected>().assert().isEmpty()
        active.abandoned[name].assert().isEqualTo(4L)
    }

    @Test
    fun `keep-alive revokes a held lease the backend no longer lists and releases unknown ones`() {
        open()
        elect(token = 1)
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val keepAlive = call<BackendRequest.KeepAlive>()
        keepAlive.request.assert().isEqualTo(BackendRequest.KeepAlive(session, config.leaseDuration, setOf(name)))
        val other = LeaseName("other")
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), mapOf(other to 6L))
        on(Event.Reply(keepAlive.requestId, BackendResult.KeptAlive(live)), now = SECOND)

        effects.filterIsInstance<Effect.Revoked>().single().reason.assert().isEqualTo(RevokeReason.TAKEN_OVER)
        call<BackendRequest.Release>().request.assert().isEqualTo(BackendRequest.Release(session, mapOf(other to 6L)))
        active.validUntil.assert().isEqualTo(SECOND + 2_950 * MILLIS)
    }

    @Test
    fun `an expired session revokes every term and opens a new session`() {
        open()
        elect()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(KeepAliveResult.Expired)))

        effects.filterIsInstance<Effect.Revoked>().single().reason.assert().isEqualTo(RevokeReason.SESSION_EXPIRED)
        call<BackendRequest.OpenSession>()
        state.elections[name].assert().isEqualTo(ElectionState.Candidate(null, null))
    }

    @Test
    fun `passing the local deadline revokes, closes the old session and opens a new one`() {
        open()
        elect()
        on(Event.Timer(active.deadlineTimer), now = active.validUntil)

        effects.filterIsInstance<Effect.Revoked>().single().reason.assert().isEqualTo(RevokeReason.DEADLINE_PASSED)
        call<BackendRequest.CloseSession>().request.assert().isEqualTo(BackendRequest.CloseSession(session))
        call<BackendRequest.OpenSession>()
    }

    @Test
    fun `a failed keep-alive suspends and retries with halving backoff`() {
        open()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val failed = BackendResult.Failed(IllegalStateException("down"))
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, failed), now = SECOND)

        active.suspended.assert().isTrue()
        val retry = effects.filterIsInstance<Effect.Arm>().single()
        // Half of the 1.95s left before the deadline.
        retry.at.assert().isEqualTo(SECOND + 975 * MILLIS)
    }

    @Test
    fun `close revokes every term and completes when the session is closed`() {
        open()
        elect()
        val done = CompletableFuture<Void>()
        on(Event.Close(done))
        effects.filterIsInstance<Effect.Revoked>().single().reason.assert().isEqualTo(RevokeReason.STOPPED)
        val close = call<BackendRequest.CloseSession>()

        on(Event.Reply(close.requestId, BackendResult.Done))
        effects.assert().contains(Effect.Complete(null, done))
    }

    @Test
    fun `state stays bounded across many one-off names`() {
        open()
        repeat(ONE_OFF_NAMES) { index ->
            val oneOff = LeaseName("invoice-$index")
            on(Event.Start(oneOff))
            val acquire = call<BackendRequest.Acquire>()
            on(Event.Reply(acquire.requestId, BackendResult.Acquired(mapOf(oneOff to AcquireResult.Granted(1)))))
            on(Event.Stop(oneOff, CompletableFuture()))
            val release = call<BackendRequest.Release>()
            // Every other release fails: the next keep-alive, which no longer lists the lease, forgets it instead.
            val result = if (index % 2 == 0) BackendResult.Done else BackendResult.Failed(IllegalStateException("lost"))
            on(Event.Reply(release.requestId, result))
        }
        active.abandoned.size.assert().isEqualTo(ONE_OFF_NAMES / 2)
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), emptyMap())
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = SECOND)

        state.elections.assert().isEmpty()
        state.pending.assert().isEmpty()
        active.abandoned.assert().isEmpty()
    }

    @Test
    fun `an abandoned token with a request in flight is kept`() {
        open()
        elect(token = 2)
        on(Event.StepDown(name, cooldown = 0, CompletableFuture()))
        val release = call<BackendRequest.Release>()
        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id))
        on(Event.Reply(release.requestId, BackendResult.Done))

        active.abandoned[name].assert().isEqualTo(2L)
    }

    @Test
    fun `boot is idempotent and a failed open retries later`() {
        on(Event.Boot)
        val open = call<BackendRequest.OpenSession>()
        on(Event.Boot).filterIsInstance<Effect.Call>().assert().isEmpty()
        on(Event.Reply(open.requestId, BackendResult.Failed(IllegalStateException("down"))))
        val retry = effects.filterIsInstance<Effect.Arm>().single()
        on(Event.Timer(retry.id), now = retry.at)
        call<BackendRequest.OpenSession>()
    }

    @Test
    fun `an open reply slower than the lease is closed and retried`() {
        on(Event.Boot)
        on(Event.Reply(call<BackendRequest.OpenSession>().requestId, opened()), now = 3 * SECOND)
        call<BackendRequest.CloseSession>().request.assert().isEqualTo(BackendRequest.CloseSession(session))
        (state.session is SessionState.Opening).assert().isTrue()
    }

    @Test
    fun `a session opened after close is closed again`() {
        on(Event.Boot)
        val open = call<BackendRequest.OpenSession>()
        val done = CompletableFuture<Void>()
        on(Event.Close(done))
        effects.assert().contains(Effect.Complete(null, done))
        on(Event.Reply(open.requestId, opened()))
        call<BackendRequest.CloseSession>()
        val again = CompletableFuture<Void>()
        on(Event.Close(again))
        effects.assert().contains(Effect.Complete(null, again))
        on(Event.Start(name)).assert().isEmpty()
    }

    @Test
    fun `starting twice and stopping or stepping down an unknown election only complete`() {
        open()
        on(Event.Start(name))
        on(Event.Start(name)).assert().isEmpty()
        val other = LeaseName("other")
        val stopped = CompletableFuture<Void>()
        on(Event.Stop(other, stopped)).assert().isEqualTo(listOf(Effect.Complete(other, stopped)))
        val steppedDown = CompletableFuture<Void>()
        on(Event.StepDown(other, 0, steppedDown)).assert().isEqualTo(listOf(Effect.Complete(other, steppedDown)))
    }

    @Test
    fun `a candidate stepping down cools down and releases a late grant`() {
        open()
        on(Event.Start(name))
        val acquire = call<BackendRequest.Acquire>()
        on(Event.StepDown(name, SECOND, CompletableFuture()))
        (state.elections[name] is ElectionState.Cooldown).assert().isTrue()
        on(Event.StepDown(name, SECOND, CompletableFuture()))
        on(Event.Reply(acquire.requestId, granted(1)))
        call<BackendRequest.Release>()
    }

    @Test
    fun `held-by and failed attempts retry later`() {
        open()
        on(Event.Start(name))
        val heldBy = BackendResult.Acquired(mapOf(name to AcquireResult.HeldBy(SessionId("other"), 3)))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, heldBy))
        val retry = effects.filterIsInstance<Effect.Arm>().single()
        retry.at.assert().isEqualTo(500 * MILLIS)

        on(Event.Timer(retry.id), now = retry.at)
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, BackendResult.Acquired(emptyMap())), now = retry.at)
        effects.filterIsInstance<Effect.Publish>().single().event.javaClass.simpleName.assert().isEqualTo(
            "AcquireFailed"
        )

        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id), now = SECOND)
        val failed = BackendResult.Failed(IllegalStateException("down"))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, failed), now = SECOND)
        effects.filterIsInstance<Effect.Arm>().size.assert().isEqualTo(1)
    }

    @Test
    fun `an acquire answering session expired ends the session`() {
        open()
        on(Event.Start(name))
        val expired = BackendResult.Failed(me.ahoo.simba.spi.SessionExpiredException(session))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, expired))
        call<BackendRequest.OpenSession>()
    }

    @Test
    fun `a keep-alive failing with session expired or an unexpected result is handled`() {
        open()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.Done), now = SECOND)
        active.suspended.assert().isTrue()
        on(Event.Timer(active.renewTimer!!), now = 2 * SECOND)
        val expired = BackendResult.Failed(me.ahoo.simba.spi.SessionExpiredException(session))
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, expired), now = 2 * SECOND)
        call<BackendRequest.OpenSession>()
    }

    @Test
    fun `a renewal after a suspension resumes idle candidates`() {
        open()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        on(
            Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.Failed(IllegalStateException())),
            now = SECOND
        )
        on(Event.Start(name), now = SECOND)
        effects.filterIsInstance<Effect.Call>().assert().isEmpty()
        on(Event.Timer(active.renewTimer!!), now = 2 * SECOND)
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), emptyMap())
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = 2 * SECOND)
        call<BackendRequest.Acquire>()
    }

    @Test
    fun `a failed keep-alive past the deadline or a late renewal expires the session`() {
        open()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val keepAlive = call<BackendRequest.KeepAlive>()
        on(Event.Reply(keepAlive.requestId, BackendResult.Failed(IllegalStateException())), now = 3 * SECOND)
        call<BackendRequest.OpenSession>()

        state = EngineState.initial(config, 7)
        open()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val live = KeepAliveResult.Live(Duration.ZERO, emptyMap())
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = 3 * SECOND)
        call<BackendRequest.OpenSession>()
    }

    @Test
    fun `a deadline timer before the deadline re-arms`() {
        open()
        on(Event.Timer(active.deadlineTimer), now = SECOND)
        effects.filterIsInstance<Effect.Arm>().single().at.assert().isEqualTo(active.validUntil)
    }

    @Test
    fun `a released hint makes an idle candidate try at once`() {
        open()
        on(Event.Start(name))
        val heldBy = BackendResult.Acquired(mapOf(name to AcquireResult.HeldBy(SessionId("other"), 3)))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, heldBy))
        on(Event.Hint(me.ahoo.simba.spi.BackendHint.MembersChanged("g"))).assert().isEmpty()
        on(Event.Hint(me.ahoo.simba.spi.BackendHint.Released(name)))
        call<BackendRequest.Acquire>()
        on(Event.Hint(me.ahoo.simba.spi.BackendHint.Released(name))).assert().isEmpty()
        on(Event.Timer(Long.MAX_VALUE)).assert().isEmpty()
        on(Event.WorkExited(name, 1)).assert().isEmpty()
    }

    @Test
    fun `a released hint during an attempt that loses makes the candidate try again at once`() {
        open()
        on(Event.Start(name))
        val heldBy = BackendResult.Acquired(mapOf(name to AcquireResult.HeldBy(SessionId("other"), 3)))
        val first = call<BackendRequest.Acquire>()
        // The reply was read before the release, but the hint overtakes it.
        on(Event.Hint(me.ahoo.simba.spi.BackendHint.Released(name))).assert().isEmpty()
        on(Event.Reply(first.requestId, heldBy))
        val again = call<BackendRequest.Acquire>()
        effects.filterIsInstance<Effect.Arm>().assert().isEmpty()

        on(Event.Reply(again.requestId, heldBy))
        effects.filterIsInstance<Effect.Call>().assert().isEmpty()
        effects.filterIsInstance<Effect.Arm>().size.assert().isEqualTo(1)
    }

    @Test
    fun `replies of unknown or other-session requests are ignored`() {
        open()
        on(Event.Reply(Long.MAX_VALUE, BackendResult.Done)).assert().isEmpty()
        on(Event.Start(name))
        val acquire = call<BackendRequest.Acquire>()
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, BackendResult.Done))
        val again = on(Event.Reply(acquire.requestId, granted(1)))
        again.assert().isEmpty()
    }

    @Test
    fun `expiry keeps cooldowns, timers after close are ignored, and in-flight releases keep their tokens`() {
        open()
        elect(token = 3)
        val other = LeaseName("other")
        on(Event.Start(other))
        on(Event.StepDown(other, SECOND, CompletableFuture()))
        on(Event.Stop(name, CompletableFuture()))
        val release = call<BackendRequest.Release>()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val keepAlive = call<BackendRequest.KeepAlive>()
        on(
            Event.Reply(
                keepAlive.requestId,
                BackendResult.KeptAlive(KeepAliveResult.Live(Duration.ofSeconds(3), emptyMap()))
            )
        )
        active.abandoned[name].assert().isEqualTo(3L)
        on(Event.Reply(release.requestId, BackendResult.Done))
        active.abandoned.assert().isEmpty()

        on(Event.Timer(active.renewTimer!!), now = 2 * SECOND)
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(KeepAliveResult.Expired)))
        (state.elections[other] is ElectionState.Cooldown).assert().isTrue()

        on(Event.Close(CompletableFuture()))
        on(Event.Timer(1)).assert().isEmpty()
    }

    @Test
    fun `jitter comes from the seeded state and replays exactly`() {
        val jittered = config.copy(retryJitter = 500 * MILLIS)
        fun firstRetry(seed: Long): Long {
            state = EngineState.initial(jittered, seed)
            on(Event.Boot)
            on(Event.Reply(call<BackendRequest.OpenSession>().requestId, BackendResult.Failed(IllegalStateException())))
            return effects.filterIsInstance<Effect.Arm>().single().at
        }
        val first = firstRetry(1)
        firstRetry(1).assert().isEqualTo(first)
        (first in 500 * MILLIS until SECOND).assert().isTrue()
    }

    companion object {
        const val ONE_OFF_NAMES = 1_000
        const val MILLIS = 1_000_000L
        const val SECOND = 1_000 * MILLIS
    }
}
