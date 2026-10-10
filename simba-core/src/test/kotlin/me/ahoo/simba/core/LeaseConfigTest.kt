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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class LeaseConfigTest {
    @Test
    fun `derives millisecond values`() {
        val config = LeaseConfig(Duration.ofSeconds(10), Duration.ofSeconds(6), Duration.ofMillis(5))

        config.ttlMillis.assert().isEqualTo(10_000)
        config.transitionMillis.assert().isEqualTo(6_000)
        config.leaseMillis.assert().isEqualTo(16_000)
        config.initialDelayMillis.assert().isEqualTo(5)
    }

    @Test
    fun `rejects invalid durations`() {
        assertThrows<IllegalArgumentException> { LeaseConfig(Duration.ZERO, Duration.ZERO) }
        assertThrows<IllegalArgumentException> { LeaseConfig(Duration.ofNanos(1), Duration.ZERO) }
        assertThrows<IllegalArgumentException> { LeaseConfig(Duration.ofMillis(1), Duration.ofMillis(-1)) }
        assertThrows<IllegalArgumentException> {
            LeaseConfig(Duration.ofMillis(1), Duration.ZERO, Duration.ofMillis(-1))
        }
        assertThrows<IllegalArgumentException> { LeaseConfig(Duration.ofMillis(Long.MAX_VALUE), Duration.ofMillis(1)) }
        assertThrows<IllegalArgumentException> { LeaseConfig(Duration.ofSeconds(Long.MAX_VALUE), Duration.ZERO) }
        assertThrows<IllegalArgumentException> {
            LeaseConfig(Duration.ofMillis(1), Duration.ZERO, Duration.ofSeconds(Long.MAX_VALUE))
        }
    }
}
