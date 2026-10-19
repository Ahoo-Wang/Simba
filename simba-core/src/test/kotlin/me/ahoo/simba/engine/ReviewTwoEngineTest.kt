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

import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Renewal against a backend granting less than the configured lease, close of groups, and event kinds. */
class ReviewTwoEngineTest : EngineHarness() {
    private val short = Duration.ofMillis(1_200)

    @Test
    fun `a backend granting less than the lease makes the session renew every third of its validity, warning once`() {
        on(Event.Boot)
        on(Event.Reply(call<BackendRequest.OpenSession>().requestId, BackendResult.Opened(SessionGrant(session, short))))
        // 1.2 s × 0.99 − 20 ms = 1.168 s of local validity: two keep-alives a second apart no longer fit.
        val every = (1_168 * MILLIS) / 3
        effects.assert().contains(Effect.Arm(active.renewTimer!!, every))
        val warned = effects.filterIsInstance<Effect.Publish>().map { it.event }
            .filterIsInstance<SimbaEvent.SessionLeaseShortened>().single()
        warned.assert().isEqualTo(SimbaEvent.SessionLeaseShortened(session, short, Duration.ofNanos(every)))

        on(Event.Timer(active.renewTimer!!), now = every)
        val live = KeepAliveResult.Live(short, emptyMap())
        on(Event.Reply(call<BackendRequest.KeepAlive>().requestId, BackendResult.KeptAlive(live)), now = every)
        effects.assert().contains(Effect.Arm(active.renewTimer!!, 2 * every))
        effects.filterIsInstance<Effect.Publish>().map { it.event }
            .filterIsInstance<SimbaEvent.SessionLeaseShortened>().assert().isEmpty()
    }

    @Test
    fun `a backend granting the full lease keeps the configured renew interval`() {
        open()
        effects.assert().contains(Effect.Arm(active.renewTimer!!, config.renewInterval))
        effects.filterIsInstance<Effect.Publish>().map { it.event }
            .filterIsInstance<SimbaEvent.SessionLeaseShortened>().assert().isEmpty()
    }

    @Test
    fun `close publishes an empty view for every group it was in`() {
        open()
        on(Event.Join("billing"))
        on(Event.Reply(call<BackendRequest.Join>().requestId, BackendResult.Done))
        on(Event.Reply(call<BackendRequest.Members>().requestId, BackendResult.Members(listOf(session))))
        on(Event.Close(CompletableFuture()))

        effects.assert().contains(Effect.Members("billing", null, emptyList()))
    }

    @Test
    fun `a failed acquire is labelled with the kind of its election`() {
        open()
        on(Event.Start(name, kind = ElectionKind.GROUP))
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, BackendResult.Failed(IllegalStateException("down"))))

        val failed = effects.filterIsInstance<Effect.Publish>().map { it.event }
            .filterIsInstance<SimbaEvent.AcquireFailed>().single()
        failed.kind.assert().isEqualTo(ElectionKind.GROUP)
    }
}
