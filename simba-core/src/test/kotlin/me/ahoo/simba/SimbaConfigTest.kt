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
        val config = SimbaConfig()
        config.renewInterval.assert().isEqualTo(Duration.ofSeconds(5))
        config.retryJitter.assert().isEqualTo(Duration.ofMillis(2500))
    }

    @Test
    fun `invalid timings are rejected`() {
        val lease = Duration.ofSeconds(3)
        listOf(
            { SimbaConfig(leaseDuration = lease, renewInterval = Duration.ZERO) },
            { SimbaConfig(leaseDuration = lease, renewInterval = Duration.ofSeconds(2)) },
            { SimbaConfig(drift = -0.1) },
            { SimbaConfig(drift = SimbaConfig.MAX_DRIFT) },
            { SimbaConfig(margin = Duration.ofMillis(-1)) },
            { SimbaConfig(retryJitter = Duration.ofMillis(-1)) },
            { SimbaConfig(minBackoff = Duration.ZERO) },
            {
                SimbaConfig(
                    leaseDuration = lease,
                    renewInterval = Duration.ofMillis(1500),
                    margin = Duration.ofMillis(1500)
                )
            },
        ).forEach { invalid -> assertThrows<IllegalArgumentException> { invalid() } }
    }
}
