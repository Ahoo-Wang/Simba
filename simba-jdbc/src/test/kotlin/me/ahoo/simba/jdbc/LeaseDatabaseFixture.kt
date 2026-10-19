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
package me.ahoo.simba.jdbc

import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Databases for [JdbcLeaseBackend], initialized with the published schema scripts of each [Dialect]: one container
 * per database and test JVM. Requires Docker.
 */
object LeaseDatabaseFixture {
    val mysql: HikariDataSource by lazy {
        MySQLContainer("mysql:8.4")
            .withDatabaseName("simba5_db")
            .withInitScript(Dialect.MYSQL.initScript)
            .dataSource()
    }

    val postgres: HikariDataSource by lazy {
        PostgreSQLContainer("postgres:17")
            .withDatabaseName("simba5_db")
            .withInitScript(Dialect.POSTGRESQL.initScript)
            .dataSource()
    }

    private fun JdbcDatabaseContainer<*>.dataSource(): HikariDataSource {
        start()
        return HikariDataSource().also {
            it.jdbcUrl = jdbcUrl
            it.username = username
            it.password = password
        }
    }
}
