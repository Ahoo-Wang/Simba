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
import java.sql.Connection
import java.sql.DriverManager

/**
 * Databases for [JdbcLeaseBackend], initialized with the published schema scripts of each [Dialect]: one container
 * per database and test JVM. Requires Docker.
 */
object LeaseDatabaseFixture {
    private val mysqlContainer: MySQLContainer by lazy {
        MySQLContainer("mysql:8.4")
            .withDatabaseName("simba5_db")
            .withInitScript(Dialect.MYSQL.initScript)
            .also { it.start() }
    }

    private val postgresContainer: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17")
            .withDatabaseName("simba5_db")
            .withInitScript(Dialect.POSTGRESQL.initScript)
            .also { it.start() }
    }

    val mysql: HikariDataSource by lazy { mysqlContainer.dataSource() }

    val postgres: HikariDataSource by lazy { postgresContainer.dataSource() }

    /** MySQL under READ COMMITTED, where reads in a statement do not lock unless asked to. */
    val mysqlReadCommitted: HikariDataSource by lazy {
        mysqlContainer.dataSource().also { it.transactionIsolation = "TRANSACTION_READ_COMMITTED" }
    }

    fun dataSource(dialect: Dialect): HikariDataSource = if (dialect == Dialect.MYSQL) mysql else postgres

    /**
     * Number of statements of the test database currently waiting for a row lock. MySQL reports lock waits only to
     * accounts with the `PROCESS` privilege, so it is read as `root` (Testcontainers gives root the same password).
     */
    fun lockWaits(dialect: Dialect): Int =
        if (dialect == Dialect.MYSQL) {
            DriverManager.getConnection(mysqlContainer.jdbcUrl, "root", mysqlContainer.password).use {
                it.count("select count(*) from information_schema.innodb_trx where trx_state = 'LOCK WAIT'")
            }
        } else {
            postgres.connection.use {
                it.count(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()"
                )
            }
        }

    private fun Connection.count(sql: String): Int =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun JdbcDatabaseContainer<*>.dataSource(): HikariDataSource =
        HikariDataSource().also {
            it.jdbcUrl = jdbcUrl
            it.username = username
            it.password = password
        }
}
