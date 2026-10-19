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
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** ADR 0010 contract 3 in the pure core: revoke, wait for the work or the deadline, release, complete. */
class HandoverTest : EngineHarness() {
    enum class Command { STOP, STEP_DOWN }

    enum class Ending {
        /** The work exits after the command, before the deadline. */
        WORK_EXITS,

        /** The work never exits; the deadline at the time of the command ends the handover. */
        DEADLINE,

        /** The work exited before the command, so the lease is released at once. */
        EXITED_BEFORE,
    }

    private fun command(command: Command, done: CompletableFuture<Void>, now: Long) {
        when (command) {
            Command.STOP -> on(Event.Stop(name, done), now)
            Command.STEP_DOWN -> on(Event.StepDown(name, SECOND, done), now)
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    fun `a term with work releases its lease only after the work exited or the deadline passed`(
        command: Command,
        ending: Ending,
    ) {
        open()
        val term = elect(token = 4, work = true)
        if (ending == Ending.EXITED_BEFORE) {
            on(Event.WorkExited(name, term)).assert().isEmpty()
        }
        val deadline = active.validUntil
        val done = CompletableFuture<Void>()
        command(command, done, now = 100 * MILLIS)
        val reason = if (command == Command.STOP) RevokeReason.STOPPED else RevokeReason.STEPPED_DOWN
        (effects.first() as Effect.Revoked).assert().isEqualTo(Effect.Revoked(name, term, reason))

        if (ending == Ending.EXITED_BEFORE) {
            (indexOf(::isRelease) < indexOf { it == Effect.Complete(name, done) }).assert().isTrue()
        } else {
            calls<BackendRequest.Release>().assert().isEmpty()
            effects.assert().doesNotContain(Effect.Complete(name, done))
            val handover = state.elections[name] as ElectionState.Handover
            handover.deadline.assert().isEqualTo(deadline)
            effects.assert().contains(Effect.Arm(handover.timer, deadline))

            val endedAt = if (ending == Ending.WORK_EXITS) 200 * MILLIS else deadline
            if (ending == Ending.WORK_EXITS) {
                on(Event.WorkExited(name, term), now = endedAt)
            } else {
                on(Event.Timer(handover.timer), now = endedAt)
            }
            val release = indexOf(::isRelease)
            (effects[release] as Effect.Call).request.assert()
                .isEqualTo(BackendRequest.Release(session, mapOf(name to 4L)))
            (release < indexOf { it == Effect.Complete(name, done) }).assert().isTrue()
            // The other ending arrives late and changes nothing.
            if (ending == Ending.WORK_EXITS) {
                on(Event.Timer(handover.timer), now = deadline).assert().isEmpty()
            } else {
                on(Event.WorkExited(name, term), now = deadline).assert().isEmpty()
            }
        }

        when (command) {
            Command.STOP -> {
                state.elections.assert().isEmpty()
                state.works.assert().isEmpty()
            }

            // The cooldown counts from the step-down: still cooling at 200ms, over by the deadline.
            Command.STEP_DOWN -> if (ending == Ending.DEADLINE) {
                (state.elections[name] is ElectionState.Candidate).assert().isTrue()
            } else {
                (state.elections[name] is ElectionState.Cooldown).assert().isTrue()
            }
        }
    }

    @Test
    fun `a term without work is released at once`() {
        open()
        elect(token = 2)
        val done = CompletableFuture<Void>()
        on(Event.Stop(name, done))

        calls<BackendRequest.Release>().assert().isEqualTo(listOf(BackendRequest.Release(session, mapOf(name to 2L))))
        effects.assert().contains(Effect.Complete(name, done))
    }

    @Test
    fun `a handover lease stays in keep-alives and is never released as an orphan`() {
        open()
        elect(token = 3, work = true)
        on(Event.Stop(name, CompletableFuture()))
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        call<BackendRequest.KeepAlive>().request.assert()
            .isEqualTo(BackendRequest.KeepAlive(session, config.leaseDuration, setOf(name)))
        val live = KeepAliveResult.Live(Duration.ofSeconds(3), mapOf(name to 3L))
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = SECOND)

        calls<BackendRequest.Release>().assert().isEmpty()
        (state.elections[name] is ElectionState.Handover).assert().isTrue()
    }

    @Test
    fun `a grant of the handover token is not released`() {
        open()
        elect(token = 3, work = true)
        on(Event.Stop(name, CompletableFuture()))
        on(Event.Hint(BackendHint.Released(name))).assert().isEmpty()
        val acquire = BackendRequest.Acquire(session, setOf(name))
        val (next, _) = step(state.copy(pending = state.pending + (999L to Pending(acquire, 0))), Event.Boot, 0)
        state = next
        on(Event.Reply(999, BackendResult.Acquired(mapOf(name to AcquireResult.Granted(3)))))

        effects.assert().isEmpty()
    }

    @Test
    fun `a restart during a stop handover contends once the lease is released`() {
        open()
        val term = elect(token = 5, work = true)
        val stopped = CompletableFuture<Void>()
        on(Event.Stop(name, stopped))
        on(Event.Start(name, work = true), now = 10 * MILLIS)
        calls<BackendRequest.Acquire>().assert().isEmpty()
        on(Event.WorkExited(name, term), now = 20 * MILLIS)

        val release = indexOf(::isRelease)
        val complete = indexOf { it == Effect.Complete(name, stopped) }
        val acquire = indexOf { it is Effect.Call && it.request is BackendRequest.Acquire }
        (release < complete && complete < acquire).assert().isTrue()
        state.works.assert().contains(name)
    }

    @Test
    fun `stop and step-down during a handover join it`() {
        open()
        val term = elect(token = 5, work = true)
        val steppedDown = CompletableFuture<Void>()
        val stopped = CompletableFuture<Void>()
        on(Event.StepDown(name, SECOND, steppedDown))
        on(Event.StepDown(name, 2 * SECOND, CompletableFuture())).assert().isEmpty()
        ((state.elections[name] as ElectionState.Handover).next).assert().isEqualTo(AfterHandover.Resume(2 * SECOND))
        on(Event.Stop(name, stopped)).assert().isEmpty()
        on(Event.StepDown(name, SECOND, CompletableFuture())).assert().isEmpty()
        ((state.elections[name] as ElectionState.Handover).next).assert().isEqualTo(AfterHandover.Stop)
        on(Event.WorkExited(name, term))

        effects.assert().contains(Effect.Complete(name, steppedDown))
        effects.assert().contains(Effect.Complete(name, stopped))
        state.elections.assert().isEmpty()
    }

    @Test
    fun `close waits for running work before closing the session`() {
        open()
        val term = elect(token = 6, work = true)
        val done = CompletableFuture<Void>()
        on(Event.Close(done))
        effects.filterIsInstance<Effect.Revoked>().single().reason.assert().isEqualTo(RevokeReason.STOPPED)
        calls<BackendRequest.CloseSession>().assert().isEmpty()
        on(Event.Start(name)).assert().isEmpty()

        on(Event.WorkExited(name, term))
        (indexOf(::isRelease) < indexOf { it is Effect.Call && it.request is BackendRequest.CloseSession })
            .assert().isTrue()
        on(Event.Reply(call<BackendRequest.CloseSession>().requestId, BackendResult.Done))
        effects.assert().contains(Effect.Complete(null, done))
    }

    @Test
    fun `a session ending during a handover keeps waiting and skips the release`() {
        open()
        val term = elect(token = 7, work = true)
        val done = CompletableFuture<Void>()
        on(Event.Stop(name, done))
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(KeepAliveResult.Expired)))
        (state.elections[name] is ElectionState.Handover).assert().isTrue()

        on(Event.WorkExited(name, term), now = SECOND)
        calls<BackendRequest.Release>().assert().isEmpty()
        effects.assert().contains(Effect.Complete(name, done))
    }

    @Test
    fun `a stop without a live deadline releases at once even with work running`() {
        open()
        elect(token = 8, work = true)
        val done = CompletableFuture<Void>()
        on(Event.Stop(name, done), now = active.validUntil)

        calls<BackendRequest.Release>().size.assert().isEqualTo(1)
        effects.assert().contains(Effect.Complete(name, done))
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> =
            Command.entries.flatMap { command -> Ending.entries.map { Arguments.of(command, it) } }
    }
}
