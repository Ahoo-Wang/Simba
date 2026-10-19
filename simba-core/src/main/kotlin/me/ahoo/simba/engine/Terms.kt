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

import me.ahoo.simba.LeaseExpiredException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Term
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The local deadline of one session incarnation, published by [Effect.Deadline]; read lock-free by its terms. */
@InternalSimbaApi
class SessionDeadline(val session: SessionId) {
    @Volatile
    var validUntil: Long = 0
        private set
    private var published = false

    fun update(validUntil: Long) {
        if (!published || validUntil - this.validUntil > 0) {
            this.validUntil = validUntil
            published = true
        }
    }
}

/** The [Term] implementation: validity is computed from [ticker] on every read. */
@InternalSimbaApi
class EngineTerm(
    override val name: LeaseName,
    override val token: Long,
    val id: Long,
    private val deadline: SessionDeadline,
    private val ticker: Ticker,
) : Term {
    val session: SessionId get() = deadline.session

    @Volatile
    var revokeReason: RevokeReason? = null
        private set
    private val revokedFuture = CompletableFuture<RevokeReason>()

    override val isValid: Boolean
        get() = revokeReason == null && ticker.nanoTime() - deadline.validUntil < 0

    override fun remaining(): Duration {
        if (revokeReason != null) {
            return Duration.ZERO
        }
        return Duration.ofNanos((deadline.validUntil - ticker.nanoTime()).coerceAtLeast(0))
    }

    override fun ensureValid(minRemaining: Duration) {
        val remaining = remaining()
        if (remaining > minRemaining) {
            return
        }
        throw LeaseExpiredException(
            "Term[$name#$token] remaining:[${remaining.toMillis()}ms] does not exceed [${minRemaining.toMillis()}ms]."
        )
    }

    override val revoked: CompletionStage<RevokeReason>
        get() = revokedFuture.minimalCompletionStage()

    fun revoke(reason: RevokeReason) {
        revokeReason = reason
        revokedFuture.complete(reason)
    }

    override fun toString(): String = "Term(name=$name, token=$token, valid=$isValid)"
}

/**
 * Applies the term-related effects ([Effect.Deadline], [Effect.Elected], [Effect.Revoked]) of one engine; shared by
 * the production shell and the simulation. Not thread-safe: call it from the engine's event loop only.
 */
@InternalSimbaApi
class TermRegistry(private val ticker: Ticker) {
    private val deadlines = HashMap<SessionId, SessionDeadline>()
    private val terms = LinkedHashMap<Long, EngineTerm>()

    /** Terms not revoked yet. */
    val active: Collection<EngineTerm> get() = terms.values

    /** The active term with id [id], if any. */
    fun term(id: Long): EngineTerm? = terms[id]

    fun deadline(effect: Effect.Deadline) {
        if (effect.session !in deadlines) {
            deadlines.keys.retainAll(terms.values.mapTo(HashSet()) { it.session })
        }
        deadlines.getOrPut(effect.session) { SessionDeadline(effect.session) }.update(effect.validUntil)
    }

    fun elected(effect: Effect.Elected): EngineTerm {
        val deadline = checkNotNull(
            deadlines[effect.session]
        ) { "No deadline published for session ${effect.session}." }
        val term = EngineTerm(effect.name, effect.token, effect.term, deadline, ticker)
        terms[effect.term] = term
        return term
    }

    fun revoked(effect: Effect.Revoked): EngineTerm {
        val term = checkNotNull(terms.remove(effect.term)) { "Unknown term ${effect.term}." }
        term.revoke(effect.reason)
        return term
    }
}
