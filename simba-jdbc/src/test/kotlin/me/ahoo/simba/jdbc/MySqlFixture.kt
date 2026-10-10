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
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

/**
 * MySQL for the JDBC tests: one container per test JVM, initialized with the published
 * `init-simba-mysql.sql`, so the tests and users share one schema definition. Requires Docker.
 */
object MySqlFixture {
    private const val IMAGE = "mysql:8.4"

    private val container: MySQLContainer by lazy {
        MySQLContainer(IMAGE)
            .withDatabaseName("simba_db")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("src/init-script/init-simba-mysql.sql")),
                "/docker-entrypoint-initdb.d/init-simba-mysql.sql"
            )
            .also { it.start() }
    }

    fun newDataSource(): HikariDataSource {
        return HikariDataSource().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
        }
    }
}
