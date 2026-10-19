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

import me.ahoo.simba.TermExpiredException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class TermsTest {
    private var now = 0L
    private val registry = TermRegistry { now }
    private val name = LeaseName("orders")

    @Test
    fun `a term reads its session deadline and ends for good when revoked`() {
        val session = SessionId("s1")
        registry.deadline(Effect.Deadline(session, 1_000))
        registry.deadline(Effect.Deadline(session, 500))
        val term = registry.elected(Effect.Elected(name, 1, 7, session))

        term.remaining().assert().isEqualTo(Duration.ofNanos(1_000))
        term.ensureValid(Duration.ofNanos(999))
        assertThrows<TermExpiredException> { term.ensureValid(Duration.ofNanos(1_000)) }
        term.toString().assert().contains("token=7")
        now = 1_000
        term.isValid.assert().isFalse()
        term.remaining().assert().isEqualTo(Duration.ZERO)
        now = 0

        registry.revoked(Effect.Revoked(name, 1, RevokeReason.STOPPED))
        term.isValid.assert().isFalse()
        term.remaining().assert().isEqualTo(Duration.ZERO)
        term.revokeReason.assert().isEqualTo(RevokeReason.STOPPED)
        registry.active.assert().isEmpty()
    }

    @Test
    fun `a new session drops deadlines no live term uses`() {
        registry.deadline(Effect.Deadline(SessionId("s1"), 1_000))
        registry.deadline(Effect.Deadline(SessionId("s2"), 2_000))
        assertThrows<IllegalStateException> { registry.elected(Effect.Elected(name, 1, 1, SessionId("s1"))) }
        assertThrows<IllegalStateException> { registry.revoked(Effect.Revoked(name, 9, RevokeReason.STOPPED)) }
    }
}
