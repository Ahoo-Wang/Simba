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
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import java.time.Duration

/** Drives the pure core one event at a time and keeps the effects of the last step. */
abstract class EngineHarness {
    protected val config = EngineConfig.of(
        SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofSeconds(1)).drift(0.01).margin(Duration.ofMillis(20)).retryJitter(Duration.ZERO).build()
    )
    protected val name = LeaseName("orders")
    protected val session = SessionId("s1")

    protected var state = EngineState.initial(config, 7)
    protected var effects: List<Effect> = emptyList()

    protected fun on(event: Event, now: Long = 0): List<Effect> {
        val (next, produced) = step(state, event, now)
        state = next
        effects = produced
        return produced
    }

    protected inline fun <reified R : BackendRequest> call(): Effect.Call =
        effects.filterIsInstance<Effect.Call>().single { it.request is R }

    protected inline fun <reified R : BackendRequest> calls(): List<R> =
        effects.filterIsInstance<Effect.Call>().map { it.request }.filterIsInstance<R>()

    protected fun open(now: Long = 0) {
        on(Event.Boot, now)
        on(Event.Reply(call<BackendRequest.OpenSession>().requestId, opened()), now)
    }

    protected fun opened(id: SessionId = session) = BackendResult.Opened(SessionGrant(id, Duration.ofSeconds(3)))

    protected fun granted(token: Long, lease: LeaseName = name) =
        BackendResult.Acquired(mapOf(lease to AcquireResult.Granted(token)))

    /** Starts [name] and adopts [token]; returns the term id. */
    protected fun elect(token: Long = 1, work: Boolean = false, now: Long = 0): Long {
        on(Event.Start(name, work = work), now)
        on(Event.Reply(call<BackendRequest.Acquire>().requestId, granted(token)), now)
        return effects.filterIsInstance<Effect.Elected>().single().term
    }

    protected val active get() = state.session as SessionState.Active

    /** Index of the first effect matching [predicate], or -1. */
    protected fun indexOf(predicate: (Effect) -> Boolean): Int = effects.indexOfFirst(predicate)

    protected fun isRelease(effect: Effect): Boolean = effect is Effect.Call && effect.request is BackendRequest.Release

    companion object {
        const val MILLIS = 1_000_000L
        const val SECOND = 1_000 * MILLIS
    }
}
