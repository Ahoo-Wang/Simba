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

import me.ahoo.simba.jdbc.Dialect
import me.ahoo.simba.jdbc.JdbcLeaseBackend
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.boot.starter.SimbaProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * A [JdbcLeaseBackend] over the single `DataSource`; the schema (`simba_session`, `simba_lease`, `simba_member`)
 * comes from the dialect's init script, `me/ahoo/simba/jdbc/init-simba-{mysql,postgresql}.sql`.
 */
@AutoConfiguration(
    afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"]
)
@ConditionalOnSimbaJdbcEnabled
@ConditionalOnClass(JdbcLeaseBackend::class)
@EnableConfigurationProperties(JdbcProperties::class, SimbaProperties::class)
class SimbaJdbcAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LeaseBackend::class)
    @ConditionalOnSingleCandidate(DataSource::class)
    fun jdbcLeaseBackend(
        dataSource: DataSource,
        properties: JdbcProperties,
        simbaProperties: SimbaProperties,
    ): JdbcLeaseBackend {
        val dialect = when (properties.dialect ?: detect(dataSource)) {
            JdbcProperties.JdbcDialect.MYSQL -> Dialect.MYSQL
            JdbcProperties.JdbcDialect.POSTGRESQL -> Dialect.POSTGRESQL
        }
        val queryTimeout = properties.queryTimeout ?: simbaProperties.session.toConfig().renewInterval
        return JdbcLeaseBackend(dataSource, dialect, queryTimeout)
    }

    private fun detect(dataSource: DataSource): JdbcProperties.JdbcDialect {
        val product = dataSource.connection.use { it.metaData.databaseProductName }
        return when {
            product.contains("PostgreSQL", ignoreCase = true) -> JdbcProperties.JdbcDialect.POSTGRESQL
            product.contains("MySQL", ignoreCase = true) -> JdbcProperties.JdbcDialect.MYSQL
            else -> throw IllegalStateException(
                "Simba has no JDBC dialect for [$product]; set ${JdbcProperties.PREFIX}.dialect to mysql or postgresql."
            )
        }
    }
}
