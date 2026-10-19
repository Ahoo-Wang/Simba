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

package me.ahoo.simba.testing

import me.ahoo.simba.RevokeReason
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.ElectionSnapshot
import me.ahoo.simba.event.ElectionStatus
import me.ahoo.simba.event.SessionSnapshot
import me.ahoo.simba.event.SessionStatus
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaSnapshot
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.Capabilities
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

/**
 * The public value types are plain classes: equality by every property (each one checked on its own), a matching
 * hash code, and a readable `toString`.
 */
class ValueTypesTest {
    /** A type built from a list of property values; [variants] differ from [base] in every position. */
    class Case(val type: String, val make: (List<Any?>) -> Any, val base: List<Any?>, val variants: List<Any?>) {
        override fun toString(): String = type
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `equality covers every property`(case: Case) {
        val value = case.make(case.base)
        val same = case.make(case.base)
        (value == value).assert().isTrue()
        value.assert().isEqualTo(same)
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value.equals("other")).assert().isFalse()
        value.toString().assert().startsWith(case.type + "(")
        for (index in case.base.indices) {
            val changed = case.base.toMutableList().also { it[index] = case.variants[index] }
            (value == case.make(changed)).assert().isFalse()
        }
    }

    companion object {
        private val s1 = SessionId("s1")
        private val s2 = SessionId("s2")
        private val a = LeaseName("a")
        private val b = LeaseName("b")
        private val one = Duration.ofSeconds(1)
        private val two = Duration.ofSeconds(2)
        private val boom = IllegalStateException("boom")
        private val bang = IllegalStateException("bang")

        @Suppress("UNCHECKED_CAST")
        private fun <T> List<Any?>.at(index: Int): T = this[index] as T

        private fun config(values: List<Any?>): SimbaConfig =
            SimbaConfig.builder()
                .leaseDuration(values.at(0))
                .renewInterval(values.at(1))
                .drift(values.at(2))
                .margin(values.at(3))
                .retryJitter(values.at(4))
                .minBackoff(values.at(5))
                .build()

        @JvmStatic
        fun cases(): List<Arguments> = (eventCases() + otherCases() + spiCases() + testingCases()).map {
            Arguments.of(
                it
            )
        }

        private fun eventCases(): List<Case> =
            listOf(
                Case("SessionOpened", { SimbaEvent.SessionOpened(it.at(0)) }, listOf(s1), listOf(s2)),
                Case(
                    "SessionRenewed",
                    { SimbaEvent.SessionRenewed(it.at(0), it.at(1)) },
                    listOf(s1, one),
                    listOf(s2, two)
                ),
                Case(
                    "SessionSuspended",
                    { SimbaEvent.SessionSuspended(it.at(0), it.at(1)) },
                    listOf(s1, boom),
                    listOf(s2, bang)
                ),
                Case(
                    "SessionExpired",
                    { SimbaEvent.SessionExpired(it.at(0), it.at(1)) },
                    listOf(s1, RevokeReason.DEADLINE_PASSED),
                    listOf(s2, RevokeReason.SESSION_EXPIRED),
                ),
                Case(
                    "SessionLeaseShortened",
                    { SimbaEvent.SessionLeaseShortened(it.at(0), it.at(1), it.at(2)) },
                    listOf(s1, one, one),
                    listOf(s2, two, two),
                ),
                Case("SessionClosed", { SimbaEvent.SessionClosed(it.at(0)) }, listOf(s1), listOf(null)),
                Case(
                    "Elected",
                    { SimbaEvent.Elected(it.at(0), it.at(1), it.at(2)) },
                    listOf(a, 1L, ElectionKind.LOCK),
                    listOf(b, 2L, ElectionKind.GROUP),
                ),
                Case(
                    "Revoked",
                    { SimbaEvent.Revoked(it.at(0), it.at(1), it.at(2), it.at(3)) },
                    listOf(a, 1L, RevokeReason.STOPPED, ElectionKind.LOCK),
                    listOf(b, 2L, RevokeReason.TAKEN_OVER, ElectionKind.GROUP),
                ),
                Case(
                    "AcquireFailed",
                    { SimbaEvent.AcquireFailed(it.at(0), it.at(1), it.at(2)) },
                    listOf(a, boom, ElectionKind.LOCK),
                    listOf(b, bang, ElectionKind.GROUP),
                ),
                Case(
                    "WorkCompleted",
                    { SimbaEvent.WorkCompleted(it.at(0), it.at(1), it.at(2), it.at(3), it.at(4), it.at(5)) },
                    listOf(a, 1L, WorkOutcome.COMPLETED, one, null, ElectionKind.LOCK),
                    listOf(b, 2L, WorkOutcome.FAILED, two, boom, ElectionKind.GROUP),
                ),
            )

        private fun otherCases(): List<Case> =
            listOf(
                Case(
                    "SessionSnapshot",
                    { SessionSnapshot(it.at(0), it.at(1), it.at(2), it.at(3)) },
                    listOf(s1, SessionStatus.LIVE, one, one),
                    listOf(s2, SessionStatus.CLOSED, two, two),
                ),
                Case(
                    "ElectionSnapshot",
                    { ElectionSnapshot(it.at(0), it.at(1), it.at(2), it.at(3), it.at(4)) },
                    listOf(a, ElectionStatus.LEADER, 1L, one, ElectionKind.LOCK),
                    listOf(b, ElectionStatus.CANDIDATE, 2L, two, ElectionKind.GROUP),
                ),
                Case(
                    "SimbaSnapshot",
                    { SimbaSnapshot(it.at(0), it.at(1), it.at(2)) },
                    listOf(SessionSnapshot(s1, SessionStatus.LIVE, one, one), emptyList<ElectionSnapshot>(), one),
                    listOf(
                        SessionSnapshot(s2, SessionStatus.LIVE, one, one),
                        listOf(ElectionSnapshot(a, ElectionStatus.LEADER, 1L, one)),
                        two,
                    ),
                ),
                Case(
                    "SimbaConfig",
                    ::config,
                    listOf(
                        Duration.ofSeconds(9),
                        Duration.ofSeconds(3),
                        0.01,
                        Duration.ofMillis(50),
                        one,
                        Duration.ofMillis(100)
                    ),
                    listOf(
                        Duration.ofSeconds(12),
                        Duration.ofSeconds(4),
                        0.02,
                        Duration.ofMillis(60),
                        two,
                        Duration.ofMillis(200)
                    ),
                ),
            )

        private fun spiCases(): List<Case> =
            listOf(
                Case("Capabilities", { Capabilities(it.at(0), it.at(1)) }, listOf(false, false), listOf(true, true)),
                Case("SessionGrant", { SessionGrant(it.at(0), it.at(1)) }, listOf(s1, one), listOf(s2, two)),
                Case(
                    "Live",
                    { KeepAliveResult.Live(it.at(0), it.at(1)) },
                    listOf(one, mapOf(a to 1L)),
                    listOf(two, emptyMap<LeaseName, Long>())
                ),
                Case("Granted", { AcquireResult.Granted(it.at(0)) }, listOf(1L), listOf(2L)),
                Case("HeldBy", { AcquireResult.HeldBy(it.at(0), it.at(1)) }, listOf(s1, 1L), listOf(s2, 2L)),
                Case("Released", { BackendHint.Released(it.at(0)) }, listOf(a), listOf(b)),
                Case("MembersChanged", { BackendHint.MembersChanged(it.at(0)) }, listOf("g"), listOf("h")),
            )

        private fun testingCases(): List<Case> =
            listOf(
                Case("Owner", { InMemoryLeaseBackend.Owner(it.at(0), it.at(1)) }, listOf(s1, 1L), listOf(s2, 2L)),
                Case(
                    "SimulationResult",
                    {
                        SimulationResult(
                            it.at(
                                0
                            ),
                            it.at(
                                1
                            ),
                            it.at(2), it.at(3), it.at(4), it.at(5), it.at(6), it.at(7), it.at(8), it.at(9), it.at(10)
                        )
                    },
                    listOf(1L, 2, 3, 4L, 5, 6, 7, mapOf(RevokeReason.STOPPED to 1), mapOf(a to 1L), 8, mapOf("x" to 1)),
                    listOf(9L, 9, 9, 9L, 9, 9, 9, emptyMap<RevokeReason, Int>(), emptyMap<LeaseName, Long>(), 9, emptyMap<String, Int>()),
                ),
                Case(
                    "SimulationParams",
                    { SimulationParams.builder().minNodes(it.at(0)).maxNodes(it.at(1)).workShare(it.at(2)).build() },
                    listOf(2, 5, 0.5),
                    listOf(3, 6, 0.6),
                ),
            )
    }
}
