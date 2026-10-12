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

import me.ahoo.simba.test.FencingSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * The SQL recipe from the correctness guide: one conditional `UPDATE` checks the token and writes atomically.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class JdbcFencingRecipeTest : FencingSpec() {
    private val dataSource = MySqlFixture.newDataSource()

    @BeforeAll
    fun createTable() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                    create table if not exists fenced_resource (
                        id varchar(128) not null primary key,
                        fencing_token bigint unsigned not null default 0,
                        payload varchar(64) not null default ''
                    )
                    """.trimIndent()
                )
            }
        }
    }

    @AfterAll
    fun close() {
        dataSource.close()
    }

    override fun write(resource: String, fencingToken: Long): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement("insert ignore into fenced_resource (id) values (?)").use {
                it.setString(1, resource)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "update fenced_resource set payload = ?, fencing_token = ? where id = ? and fencing_token <= ?"
            ).use {
                it.setString(1, "written by $fencingToken")
                it.setLong(2, fencingToken)
                it.setString(3, resource)
                it.setLong(4, fencingToken)
                return it.executeUpdate() == 1
            }
        }
    }

    override fun highestToken(resource: String): Long {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select fencing_token from fenced_resource where id = ?").use {
                it.setString(1, resource)
                it.executeQuery().use { rows -> return if (rows.next()) rows.getLong(1) else 0 }
            }
        }
    }
}
