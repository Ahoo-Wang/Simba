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

import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** Probes (one-shot attempts for `tryAcquire`) and group membership in the pure core. */
class GroupsAndProbesTest : EngineHarness() {
    private val group = "billing"
    private val other = SessionId("s2")

    @Test
    fun `a probe completes when the first answered attempt does not elect`() {
        open()
        val probe = CompletableFuture<Void>()
        on(Event.Start(name, probe = probe))
        val acquire = call<BackendRequest.Acquire>()
        on(Event.Reply(acquire.requestId, BackendResult.Acquired(mapOf(name to AcquireResult.HeldBy(other, 3)))))

        effects.assert().contains(Effect.Complete(name, probe))
        (state.elections[name] as ElectionState.Candidate).probe.assert().isNull()
    }

    @Test
    fun `a probe waits for the session and is dropped on election`() {
        val probe = CompletableFuture<Void>()
        on(Event.Start(name, probe = probe))
        open()
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(1)))

        effects.filterIsInstance<Effect.Elected>().size.assert().isEqualTo(1)
        effects.assert().doesNotContain(Effect.Complete(name, probe))
    }

    private fun joined(): Effect.Members {
        on(Event.Join(group))
        val join = call<BackendRequest.Join>()
        join.request.assert().isEqualTo(BackendRequest.Join(session, group))
        on(Event.Reply(join.requestId, BackendResult.Done))
        val members = call<BackendRequest.Members>()
        on(Event.Reply(members.requestId, BackendResult.Members(listOf(session, other))))
        return effects.filterIsInstance<Effect.Members>().single()
    }

    @Test
    fun `joining publishes the members seen by this session`() {
        open()
        joined().assert().isEqualTo(Effect.Members(group, session, listOf(session, other)))
        on(Event.Join(group)).assert().isEmpty()
    }

    @Test
    fun `a group joins once the session is live`() {
        on(Event.Join(group))
        calls<BackendRequest.Join>().assert().isEmpty()
        open()
        calls<BackendRequest.Join>().assert().isEqualTo(listOf(BackendRequest.Join(session, group)))
    }

    @Test
    fun `members refresh every renew interval and on hints, publishing only changes`() {
        open()
        joined()
        val refresh = effects.filterIsInstance<Effect.Arm>().single()
        refresh.at.assert().isEqualTo(config.renewInterval)
        on(Event.Timer(refresh.id), now = SECOND)
        on(Event.Reply(call<BackendRequest.Members>().requestId, BackendResult.Members(listOf(session, other))))
        effects.filterIsInstance<Effect.Members>().assert().isEmpty()

        on(Event.Hint(BackendHint.MembersChanged(group)), now = SECOND)
        val hinted = call<BackendRequest.Members>()
        on(Event.Hint(BackendHint.MembersChanged(group)), now = SECOND).assert().isEmpty()
        on(Event.Reply(hinted.requestId, BackendResult.Members(listOf(session))))
        effects.filterIsInstance<Effect.Members>().single().members.assert().isEqualTo(listOf(session))
        on(Event.Hint(BackendHint.MembersChanged("unknown"))).assert().isEmpty()
    }

    @Test
    fun `a membership hint during a members read reads the members again at once`() {
        open()
        joined()
        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id), now = SECOND)
        val stale = call<BackendRequest.Members>()
        on(Event.Hint(BackendHint.MembersChanged(group)), now = SECOND).assert().isEmpty()
        on(Event.Reply(stale.requestId, BackendResult.Members(listOf(session, other))), now = SECOND)
        val fresh = call<BackendRequest.Members>()
        effects.filterIsInstance<Effect.Arm>().assert().isEmpty()

        on(Event.Reply(fresh.requestId, BackendResult.Members(listOf(session))), now = SECOND)
        effects.filterIsInstance<Effect.Members>().single().members.assert().isEqualTo(listOf(session))
        effects.filterIsInstance<Effect.Arm>().single().at.assert().isEqualTo(SECOND + config.renewInterval)
    }

    @Test
    fun `failed joins and refreshes retry later`() {
        open()
        on(Event.Join(group))
        on(Event.Reply(call<BackendRequest.Join>().requestId, BackendResult.Failed(IllegalStateException("down"))))
        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id), now = SECOND)
        on(Event.Reply(call<BackendRequest.Join>().requestId, BackendResult.Done), now = SECOND)
        on(Event.Reply(call<BackendRequest.Members>().requestId, BackendResult.Failed(IllegalStateException())))
        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id), now = 2 * SECOND)

        calls<BackendRequest.Members>().size.assert().isEqualTo(1)
    }

    @Test
    fun `a join answering session expired ends the session`() {
        open()
        on(Event.Join(group))
        val failed = BackendResult.Failed(SessionExpiredException(session))
        on(Event.Reply(call<BackendRequest.Join>().requestId, failed))

        call<BackendRequest.OpenSession>()
    }

    @Test
    fun `an expired session empties the view and the next session joins again`() {
        open()
        joined()
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(KeepAliveResult.Expired)))
        effects.filterIsInstance<Effect.Members>().single().assert().isEqualTo(Effect.Members(group, null, emptyList()))

        val next = SessionId("s3")
        on(Event.Reply(call<BackendRequest.OpenSession>().requestId, opened(next)), now = SECOND)
        calls<BackendRequest.Join>().assert().isEqualTo(listOf(BackendRequest.Join(next, group)))
    }

    @Test
    fun `leave sends a leave for a joined group and forgets it`() {
        open()
        joined()
        on(Event.Leave(group))
        val leave = call<BackendRequest.Leave>()
        leave.request.assert().isEqualTo(BackendRequest.Leave(session, group))
        on(Event.Leave(group)).assert().isEmpty()
        on(Event.Hint(BackendHint.MembersChanged(group))).assert().isEmpty()
        on(Event.Reply(leave.requestId, BackendResult.Done)).assert().isEmpty()
        state.groups.assert().isEmpty()
    }

    @Test
    fun `a failed leave retries until the backend confirms it`() {
        open()
        joined()
        on(Event.Leave(group))
        on(Event.Reply(call<BackendRequest.Leave>().requestId, BackendResult.Failed(IllegalStateException("down"))))
        on(Event.Timer(effects.filterIsInstance<Effect.Arm>().single().id), now = SECOND)
        val retry = call<BackendRequest.Leave>()
        on(Event.Reply(retry.requestId, BackendResult.Done), now = SECOND)
        state.groups.assert().isEmpty()
    }

    @Test
    fun `a leave while joining is sent once the join answers, even a failed one`() {
        for (answer in listOf(BackendResult.Done, BackendResult.Failed(IllegalStateException("timeout")))) {
            state = EngineState.initial(config, 7)
            open()
            on(Event.Join(group))
            val join = call<BackendRequest.Join>()
            on(Event.Leave(group)).assert().isEmpty()
            on(Event.Reply(join.requestId, answer))
            calls<BackendRequest.Leave>().assert().isEqualTo(listOf(BackendRequest.Leave(session, group)))
            effects.filterIsInstance<Effect.Members>().assert().isEmpty()
        }
    }

    @Test
    fun `joining again while leaving rejoins after the leave`() {
        open()
        joined()
        on(Event.Leave(group))
        val leave = call<BackendRequest.Leave>()
        on(Event.Join(group)).assert().isEmpty()
        on(Event.Reply(leave.requestId, BackendResult.Done))
        calls<BackendRequest.Join>().assert().isEqualTo(listOf(BackendRequest.Join(session, group)))
    }

    @Test
    fun `a session ending while leaving forgets the group`() {
        open()
        joined()
        on(Event.Leave(group))
        on(Event.Timer(active.renewTimer!!), now = SECOND)
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(KeepAliveResult.Expired)))
        state.groups.assert().isEmpty()
    }

    @Test
    fun `leaving without a session forgets the group at once`() {
        on(Event.Join(group))
        on(Event.Leave(group)).assert().isEmpty()
        state.groups.assert().isEmpty()
    }

    @Test
    fun `close forgets groups`() {
        open()
        joined()
        on(Event.Close(CompletableFuture()))
        state.groups.assert().isEmpty()
        on(Event.Join(group)).assert().isEmpty()
    }
}
