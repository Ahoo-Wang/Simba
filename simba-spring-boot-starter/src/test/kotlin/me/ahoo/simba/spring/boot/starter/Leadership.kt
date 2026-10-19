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

package me.ahoo.simba.spring.boot.starter

import me.ahoo.simba.LeadershipListener
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Term
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Records leadership callbacks for the starter tests. */
class Leadership : LeadershipListener {
    val elected = LinkedBlockingQueue<Term>()

    override fun onElected(term: Term) {
        elected += term
    }

    override fun onRevoked(term: Term, reason: RevokeReason) = Unit

    fun nextTerm(): Term = checkNotNull(elected.poll(WAIT_SECONDS, TimeUnit.SECONDS)) { "No term." }

    companion object {
        const val WAIT_SECONDS = 30L
    }
}
