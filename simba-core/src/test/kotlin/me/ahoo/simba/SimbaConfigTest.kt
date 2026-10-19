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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class SimbaConfigTest {
    @Test
    fun `defaults derive from the lease duration`() {
        val config = SimbaConfig.builder().build()
        config.renewInterval.assert().isEqualTo(Duration.ofSeconds(5))
        config.retryJitter.assert().isEqualTo(Duration.ofMillis(2500))
    }

    @Test
    fun `the builder keeps defaults and derives from the values set`() {
        SimbaConfig.builder().build().assert().isEqualTo(SimbaConfig.builder().build())
        SimbaConfig.builder().build().hashCode().assert().isEqualTo(SimbaConfig.builder().build().hashCode())
        val config = SimbaConfig.builder()
            .leaseDuration(Duration.ofSeconds(9))
            .drift(0.02)
            .margin(Duration.ofMillis(10))
            .minBackoff(Duration.ofMillis(20))
            .build()
        config.renewInterval.assert().isEqualTo(Duration.ofSeconds(3))
        config.retryJitter.assert().isEqualTo(Duration.ofMillis(1500))
        config.drift.assert().isEqualTo(0.02)
        config.margin.assert().isEqualTo(Duration.ofMillis(10))
        config.minBackoff.assert().isEqualTo(Duration.ofMillis(20))
        config.toString().assert().contains("leaseDuration=PT9S")
        (config == SimbaConfig.builder().build()).assert().isFalse()
    }

    @Test
    fun `toBuilder keeps every value, derived ones included`() {
        val config = SimbaConfig.builder().leaseDuration(Duration.ofSeconds(9)).build()
        config.toBuilder().build().assert().isEqualTo(config)
        // The renew interval stays the one derived from 9 s, not one derived from the new lease.
        config.toBuilder().leaseDuration(Duration.ofSeconds(30)).build().renewInterval.assert()
            .isEqualTo(Duration.ofSeconds(3))
    }

    @Test
    fun `there is no public constructor, only the builder`() {
        SimbaConfig::class.java.constructors.filterNot { it.isSynthetic }.assert().isEmpty()
        SimbaConfig::class.java.methods.map { it.name }.assert().doesNotContain("copy", "component1")
    }

    @Test
    fun `invalid timings are rejected`() {
        val lease = Duration.ofSeconds(3)
        listOf(
            { SimbaConfig.builder().leaseDuration(lease).renewInterval(Duration.ZERO).build() },
            { SimbaConfig.builder().leaseDuration(lease).renewInterval(Duration.ofSeconds(2)).build() },
            { SimbaConfig.builder().drift(-0.1).build() },
            { SimbaConfig.builder().drift(SimbaConfig.MAX_DRIFT).build() },
            { SimbaConfig.builder().margin(Duration.ofMillis(-1)).build() },
            { SimbaConfig.builder().retryJitter(Duration.ofMillis(-1)).build() },
            { SimbaConfig.builder().minBackoff(Duration.ZERO).build() },
            {
                SimbaConfig.builder().leaseDuration(lease).renewInterval(Duration.ofMillis(1500)).margin(Duration.ofMillis(1500)).build()
            },
        ).forEach { invalid -> assertThrows<IllegalArgumentException> { invalid() } }
    }
}
