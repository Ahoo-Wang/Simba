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
 * One term of this node's ownership of a lease: an immutable identity plus a read-only view of time.
 *
 * A node that wins the same name twice gets two different terms. Validity is a pure function of the monotonic
 * clock and the session's local deadline, so a paused process reads the correct answer on its first read after
 * waking up, and an ended term never becomes valid again even if the node is re-elected.
 *
 * Validity is only a hint for when to start a step; only a resource that checks [token] can reject a stale owner.
 */
interface Term {
    val name: LeaseName

    /** Fencing token: greater than zero, constant within the term, strictly increasing across terms of [name]. */
    val token: Long

    /** `true` while the term is not revoked and the local deadline has not passed. */
    val isValid: Boolean

    /** Time left before the local deadline; [Duration.ZERO] once the term ended. */
    fun remaining(): Duration

    /** Throws [LeaseExpiredException] unless the term is valid for more than [minRemaining]. */
    fun ensureValid(minRemaining: Duration = Duration.ZERO)

    /** Completes exactly once, when the engine revokes the term. */
    val revoked: CompletionStage<RevokeReason>
}

/** Why a term ended. */
enum class RevokeReason {
    /** The election was stopped or the client closed. */
    STOPPED,

    /** The election stepped down. */
    STEPPED_DOWN,

    /** The backend reported the session expired. */
    SESSION_EXPIRED,

    /** The session's local deadline passed without a successful keep-alive. */
    DEADLINE_PASSED,

    /** The backend reported the lease owned by someone else. */
    TAKEN_OVER,
}

/** Thrown by [Term.ensureValid] when the term ends too soon to start the next step. */
class LeaseExpiredException(message: String) : SimbaException(message)
