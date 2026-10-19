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

package me.ahoo.simba

import me.ahoo.simba.spi.LeaseName
import java.time.Duration
import java.util.concurrent.CompletionStage

/**
 * Leader election for one lease name on one client session.
 *
 * Contract:
 * - every [LeadershipListener.onElected] is followed by exactly one [LeadershipListener.onRevoked] for the same
 *   term, and callbacks of one election run one at a time, in order;
 * - the engine never waits for a callback, except through the futures returned by [stop] and [stepDown];
 * - after the future returned by [stop] completes, the listener receives no more callbacks.
 */
interface Election {
    val name: LeaseName

    /** Starts contending; throws [IllegalStateException] when already started. */
    fun start(listener: LeadershipListener)

    /** Stops contending; completes after the last `onRevoked` ran. Completes at once when not started. */
    fun stop(): CompletionStage<Void>

    /** Gives up the current term (if any) and does not contend for [cooldown]; the election stays started. */
    fun stepDown(cooldown: Duration): CompletionStage<Void>
}

/** Callbacks of an [Election]. Keep them short: they run on the election's serial dispatcher. */
interface LeadershipListener {
    fun onElected(term: Term)

    fun onRevoked(term: Term, reason: RevokeReason)
}
