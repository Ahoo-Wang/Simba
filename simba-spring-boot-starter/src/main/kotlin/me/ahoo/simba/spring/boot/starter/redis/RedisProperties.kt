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

package me.ahoo.simba.spring.boot.starter.redis

import me.ahoo.simba.spring.boot.starter.SimbaProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `simba.redis.*`.
 *
 * @param waitReplicas when positive, a new term counts only once that many replicas acknowledged it (`WAIT`), so a
 *   failover is less likely to lose a token increment (ADR 0010 §7.4); not on a Cluster.
 * @param waitTimeout how long `WAIT` waits for [waitReplicas].
 * @param routeBySlot whether to split script calls by hash slot; default on for a Redis Cluster.
 */
@ConfigurationProperties(prefix = RedisProperties.PREFIX)
data class RedisProperties(
    val enabled: Boolean = true,
    val waitReplicas: Int = 0,
    val waitTimeout: Duration = Duration.ofSeconds(1),
    val routeBySlot: Boolean? = null,
) {
    companion object {
        const val PREFIX = SimbaProperties.PREFIX + ".redis"
    }
}
