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

import me.ahoo.simba.SimbaConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `simba.*`: the backend choice and the session timing of the [me.ahoo.simba.Simba] bean (ADR 0010 §5.3, §10).
 *
 * @param backend `jdbc`, `redis` or `zookeeper`; required when several backend modules are active.
 */
@ConfigurationProperties(prefix = SimbaProperties.PREFIX)
data class SimbaProperties(
    val enabled: Boolean = true,
    val backend: String? = null,
    val session: Session = Session(),
) {
    /**
     * @param leaseDuration the backend expires the session this long after its last keep-alive.
     * @param renewInterval keep-alive period; default a third of [leaseDuration], at most half of it.
     * @param drift upper bound of the clock rate difference between a node and the backend.
     * @param margin extra safety subtracted from every local deadline.
     */
    data class Session(
        val leaseDuration: Duration = Duration.ofSeconds(DEFAULT_LEASE_SECONDS),
        val renewInterval: Duration? = null,
        val drift: Double = DEFAULT_DRIFT,
        val margin: Duration = Duration.ofMillis(DEFAULT_MARGIN_MILLIS),
    ) {
        fun toConfig(): SimbaConfig {
            val renew = renewInterval ?: leaseDuration.dividedBy(DEFAULT_RENEWALS)
            return SimbaConfig.builder().leaseDuration(leaseDuration).renewInterval(renew).drift(drift).margin(margin).retryJitter(renew.dividedBy(2)).build()
        }
    }

    companion object {
        const val PREFIX = "simba"
        private const val DEFAULT_LEASE_SECONDS = 15L
        private const val DEFAULT_RENEWALS = 3L
        private const val DEFAULT_DRIFT = 0.01
        private const val DEFAULT_MARGIN_MILLIS = 50L
    }
}
