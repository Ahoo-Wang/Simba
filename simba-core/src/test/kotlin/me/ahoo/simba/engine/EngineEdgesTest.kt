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
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Less common combinations of handovers, close, timers and groups in the pure core. */
class EngineEdgesTest : EngineHarness() {
    private val group = "billing"
    private val next = SessionId("s2")

    private fun expireAndReopen(now: Long = SECOND) {
        on(Event.Timer(active.renewTimer!!), now = now)
        on(
            Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(KeepAliveResult.Expired)),
            now
        )
        on(Event.Reply(call<BackendRequest.OpenSession>().requestId, opened(next)), now)
    }

    @Test
    fun `a start during a step-down handover is ignored`() {
        open()
        elect(work = true)
        on(Event.StepDown(name, SECOND, CompletableFuture()))
        on(Event.Start(name, work = true)).assert().isEmpty()
        (state.elections[name] as ElectionState.Handover).next.assert().isEqualTo(AfterHandover.Resume(SECOND))
    }

    @Test
    fun `a handover of an old session ends without a release and stays out of new keep-alives`() {
        open()
        val term = elect(token = 2, work = true)
        val done = CompletableFuture<Void>()
        on(Event.Stop(name, done))
        expireAndReopen()
        on(Event.Timer(active.renewTimer!!), now = 2 * SECOND)
        call<BackendRequest.KeepAlive>().request.assert()
            .isEqualTo(BackendRequest.KeepAlive(next, config.leaseDuration, emptySet()))
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), mapOf(name to 9L))
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = 2 * SECOND)
        // Another token of the name is an orphan, not the handover's lease.
        calls<BackendRequest.Release>().assert().isEqualTo(listOf(BackendRequest.Release(next, mapOf(name to 9L))))

        on(Event.WorkExited(name, term), now = 2 * SECOND)
        calls<BackendRequest.Release>().assert().isEmpty()
        effects.assert().contains(Effect.Complete(name, done))
    }

    @Test
    fun `close revokes, releases or hands over every kind of election`() {
        open()
        val working = LeaseName("working")
        val idle = LeaseName("idle")
        val handover = LeaseName("handover")
        on(Event.Start(name))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(1)))
        on(Event.Start(working, work = true))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(1, working)))
        on(Event.Start(handover, work = true))
        val handoverTerm = run {
            on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(1, handover)))
            effects.filterIsInstance<Effect.Elected>().single().term
        }
        on(Event.Stop(handover, CompletableFuture()))
        on(Event.Start(handover, work = true))
        on(Event.Start(idle))
        on(Event.Hint(BackendHint.Released(idle)))
        val done = CompletableFuture<Void>()
        on(Event.Close(done), now = 10 * MILLIS)

        effects.filterIsInstance<Effect.Revoked>().map { it.name }.assert().isEqualTo(listOf(name, working))
        state.elections.keys.assert().isEqualTo(setOf(working, handover))
        (state.elections[handover] as ElectionState.Handover).next.assert().isEqualTo(AfterHandover.Stop)
        on(Event.WorkExited(handover, handoverTerm))
        calls<BackendRequest.CloseSession>().assert().isEmpty()
        val workingTerm = (state.elections[working] as ElectionState.Handover).term
        on(Event.WorkExited(working, workingTerm))
        call<BackendRequest.CloseSession>()
    }

    @Test
    fun `close past the deadline releases working terms at once`() {
        open()
        elect(work = true)
        on(Event.Close(CompletableFuture()), now = active.validUntil)
        call<BackendRequest.CloseSession>()
        state.elections.assert().isEmpty()
    }

    @Test
    fun `cooldown and stale timers`() {
        open()
        elect()
        on(Event.StepDown(name, SECOND, CompletableFuture()))
        val cooldown = (state.elections[name] as ElectionState.Cooldown).timer
        on(Event.Timer(9_999)).assert().isEmpty()
        on(Event.Timer(cooldown), now = SECOND)
        call<BackendRequest.Acquire>()
    }

    @Test
    fun `group timers wait for a live session`() {
        open()
        on(Event.Join(group))
        on(Event.Reply(call<BackendRequest.Join>().requestId, BackendResult.Failed(IllegalStateException())))
        val retry = effects.filterIsInstance<Effect.Arm>().single().id
        on(Event.Timer(active.deadlineTimer), now = active.validUntil)
        on(Event.Timer(retry), now = 4 * SECOND).assert().isEmpty()
    }

    @Test
    fun `group replies that no longer apply are ignored`() {
        open()
        on(Event.Join(group))
        val join = call<BackendRequest.Join>()
        on(Event.Hint(BackendHint.MembersChanged(group))).assert().isEmpty()
        on(Event.Reply(join.requestId, BackendResult.Done))
        on(Event.Leave(group))
        on(Event.Reply(call<BackendRequest.Leave>().requestId, BackendResult.Done))

        on(Event.Join(group))
        val second = call<BackendRequest.Join>()
        expireAndReopen()
        val rejoin = call<BackendRequest.Join>()
        on(Event.Reply(second.requestId, BackendResult.Done)).assert().isEmpty()
        on(Event.Reply(rejoin.requestId, BackendResult.Done))
        val members = call<BackendRequest.Members>()
        on(Event.Leave(group))
        // The leave replaced the members request: its late reply publishes nothing.
        on(Event.Reply(members.requestId, BackendResult.Members(listOf(next)))).assert().isEmpty()
    }

    @Test
    fun `joining after close is ignored`() {
        open()
        on(Event.Close(CompletableFuture()))
        on(Event.Reply(call<BackendRequest.CloseSession>().requestId, BackendResult.Done))
        on(Event.Join(group)).assert().isEmpty()
        on(Event.Start(name)).assert().isEmpty()
    }

    @Test
    fun `an expiry resets candidates and the next session retries every election`() {
        open()
        elect()
        on(Event.Start(LeaseName("candidate")))
        expireAndReopen()
        effects.filterIsInstance<Effect.Call>().map { it.request }.filterIsInstance<BackendRequest.Acquire>().size
            .assert().isEqualTo(2)
    }

    @Test
    fun `a renewal arriving after the local deadline expires the session instead of reviving its terms`() {
        open()
        elect()
        val deadline = active.validUntil
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        val keepAlive = call<BackendRequest.KeepAlive>()
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), mapOf(name to 1L))
        on(Event.Reply(keepAlive.requestId, BackendResult.KeptAlive(live)), now = deadline)

        effects.filterIsInstance<Effect.Deadline>().assert().isEmpty()
        effects.filterIsInstance<Effect.Revoked>().single().reason.assert().isEqualTo(RevokeReason.DEADLINE_PASSED)
        call<BackendRequest.CloseSession>()
    }

    @Test
    fun `deadlines keep moving forward when the monotonic clock wraps`() {
        val start = Long.MAX_VALUE - 3_500 * MILLIS
        open(now = start)
        val before = active.validUntil
        (before > 0).assert().isTrue()
        on(Event.Timer(active.renewTimer!!), now = start + SECOND)
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), emptyMap())
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = start + SECOND)

        // The new deadline wrapped below zero yet is later than the old one.
        (active.validUntil < 0).assert().isTrue()
        (active.validUntil - before).assert().isEqualTo(SECOND)
        effects.assert().contains(Effect.Deadline(session, active.validUntil))
    }

    @Test
    fun `a restart with a probe during a stop handover keeps the probe`() {
        open()
        val term = elect(work = true)
        on(Event.Stop(name, CompletableFuture()))
        val probe = CompletableFuture<Void>()
        on(Event.Start(name, probe = probe))
        on(Event.WorkExited(name, term))
        (state.elections[name] as ElectionState.Candidate).probe.assert().isSameAs(probe)
        val acquire = call<BackendRequest.Acquire>()
        val held = BackendResult.Acquired(mapOf(name to AcquireResult.HeldBy(next, 9)))
        on(Event.Reply(acquire.requestId, held))
        effects.assert().contains(Effect.Complete(name, probe))
    }

    @Test
    fun `a probe of an election that is already started reports at once`() {
        open()
        elect()
        val probe = CompletableFuture<Void>()
        on(Event.Start(name, probe = probe)).assert().isEqualTo(listOf(Effect.Complete(name, probe)))
    }

    @Test
    fun `events and kinds tell which api started an election, and close reports the session closed`() {
        open()
        on(Event.Start(name, kind = ElectionKind.LOCK))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(1)))
        effects.assert().contains(Effect.Publish(SimbaEvent.Elected(name, 1, ElectionKind.LOCK)))
        state.kinds.assert().isEqualTo(mapOf(name to ElectionKind.LOCK))
        on(Event.Stop(name, CompletableFuture()))
        effects.assert().contains(Effect.Publish(SimbaEvent.Revoked(name, 1, RevokeReason.STOPPED, ElectionKind.LOCK)))
        state.kinds.assert().isEmpty()

        on(Event.Close(CompletableFuture()))
        effects.assert().contains(Effect.Publish(SimbaEvent.SessionClosed(session)))
    }

    @Test
    fun `closing without a session reports no session`() {
        on(Event.Close(CompletableFuture())).assert().contains(Effect.Publish(SimbaEvent.SessionClosed(null)))
    }
}
