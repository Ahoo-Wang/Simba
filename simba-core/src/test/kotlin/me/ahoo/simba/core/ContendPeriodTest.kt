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

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.lessThan
import org.junit.jupiter.api.Test

class ContendPeriodTest {
    @Test
    fun `owner delay uses owner clock`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )

        assertThat(ContendPeriod.nextOwnerDelay(owner), equalTo(400))
    }

    @Test
    fun `ensureNextDelay clamps negative delay to zero`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 500,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("owner")

        assertThat(period.ensureNextDelay(owner), equalTo(0L))
    }

    @Test
    fun `ensureNextDelay returns computed delay when positive`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("owner")

        assertThat(period.ensureNextDelay(owner), equalTo(400L))
    }

    @Test
    fun `nextDelay delegates to owner delay when contender is owner`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("owner")

        assertThat(period.nextDelay(owner), equalTo(ContendPeriod.nextOwnerDelay(owner)))
    }

    @Test
    fun `nextDelay delegates to contender delay when contender is not owner`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("other")

        // The owner branch would return exactly 400; the contender branch returns a
        // non-deterministic value in [800, 2000) (transitionAt - currentAt + random).
        // Asserting that range confirms the contender dispatch was taken.
        val delay = period.nextDelay(owner)

        assertThat(delay, greaterThanOrEqualTo(800L))
        assertThat(delay, lessThan(2000L))
    }

    @Test
    fun `nextContenderDelay with zero transition stays in non-negative range`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 2000,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )

        val delay = ContendPeriod.nextContenderDelay(owner)

        assertThat(delay, greaterThanOrEqualTo(1000L))
        assertThat(delay, lessThan(2000L))
    }

    @Test
    fun `nextContenderDelay with non-zero transition allows negative offset`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )

        val delay = ContendPeriod.nextContenderDelay(owner)

        assertThat(delay, greaterThanOrEqualTo(800L))
        assertThat(delay, lessThan(2000L))
    }
}
