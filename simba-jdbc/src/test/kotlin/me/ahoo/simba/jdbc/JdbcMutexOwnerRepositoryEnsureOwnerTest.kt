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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Unit test for ensureOwner's concurrent-init catch scope. No database required.
 *
 * The catch was declared for the unique-key race on concurrent initialization but
 * caught every SQLException: an unrelated init failure (connection dropped, syntax,
 * privileges) was logged as a warn and then MASKED by the subsequent getOwner's own
 * failure, sending troubleshooting after the wrong root cause.
 */
class JdbcMutexOwnerRepositoryEnsureOwnerTest {
    @Test
    fun `ensureOwner propagates the init failure instead of masking it with the retry read`() {
        val initBoom = SQLException("connection broken during init")
        val readBoom = SQLException("stale read on retry")
        val repository = JdbcMutexOwnerRepository(
            jdbcProxy(DataSource::class.java) { method, _ ->
                if (method.name == "getConnection") statementsBySql(initBoom, readBoom) else null
            }
        )

        val error = assertThrows<SQLException> {
            repository.ensureOwner("m")
        }

        assertThat(
            "the ORIGINAL init failure must propagate, not the masking retry read",
            error.message,
            containsString("connection broken during init")
        )
    }

    private fun statementsBySql(initBoom: SQLException, readBoom: SQLException): Connection {
        val selectCalls = AtomicInteger()
        return jdbcProxy(Connection::class.java) { method, args ->
            if (method.name != "prepareStatement") return@jdbcProxy null
            when {
                (args?.get(0) as String).contains("insert", ignoreCase = true) ->
                    jdbcProxy(PreparedStatement::class.java) { m, _ ->
                        if (m.name == "executeUpdate") throw initBoom
                        null
                    }

                else -> jdbcProxy(PreparedStatement::class.java) { m, _ ->
                    when (m.name) {
                        // first read: row missing -> NotFoundMutexOwnerException -> init path;
                        // retry read after a swallowed init failure: the masking failure
                        "executeQuery" ->
                            if (selectCalls.getAndIncrement() == 0) emptyResultSet() else throw readBoom

                        else -> null
                    }
                }
            }
        }
    }

    private fun emptyResultSet(): ResultSet {
        return jdbcProxy(ResultSet::class.java) { m, _ ->
            if (m.name == "next") false else null
        }
    }
}
