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

package me.ahoo.simba.spring.boot.starter.jdbc

import me.ahoo.simba.spring.boot.starter.SimbaProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `simba.jdbc.*`.
 *
 * @param dialect the database; detected from the `DataSource` when unset.
 * @param queryTimeout per-statement timeout; default the session renew interval, after which the engine gives up on
 *   the call anyway.
 */
@ConfigurationProperties(prefix = JdbcProperties.PREFIX)
data class JdbcProperties(
    val enabled: Boolean = true,
    val dialect: JdbcDialect? = null,
    val queryTimeout: Duration? = null,
) {
    enum class JdbcDialect { MYSQL, POSTGRESQL }

    companion object {
        const val PREFIX = SimbaProperties.PREFIX + ".jdbc"
    }
}
