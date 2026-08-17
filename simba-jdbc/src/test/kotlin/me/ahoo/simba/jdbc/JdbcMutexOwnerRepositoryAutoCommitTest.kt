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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * Unit test for pooled-connection state restore. No database required.
 *
 * acquireAndGetOwner flips autoCommit off for its transaction; returning the
 * connection to the pool without restoring the previous value relies on the pool
 * to reset it — a DataSource that does not would silently roll back every later
 * autoCommit-mode statement on the recycled connection.
 */
class JdbcMutexOwnerRepositoryAutoCommitTest {
    @Test
    fun `acquireAndGetOwner restores the previous autoCommit before releasing the connection`() {
        val autoCommit = AtomicBoolean(true)
        val now = System.currentTimeMillis()
        val repository = JdbcMutexOwnerRepository(
            jdbcProxy(DataSource::class.java) { method, _ ->
                if (method.name == "getConnection") recordingConnection(autoCommit, now) else null
            }
        )

        val owner = repository.acquireAndGetOwner("m", "c1", 10_000, 6_000)

        // sanity: the call completed through the "not acquired, someone else owns it" path
        assertThat(owner.ownerId, equalTo("other-owner"))
        assertThat(
            "autoCommit must be restored before the connection goes back to the pool",
            autoCommit.get(),
            equalTo(true)
        )
    }

    private fun recordingConnection(autoCommit: AtomicBoolean, now: Long): Connection {
        val statement = recordingStatement(now)
        return jdbcProxy(Connection::class.java) { method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit.get()
                "setAutoCommit" -> {
                    autoCommit.set(args?.get(0) as Boolean)
                    null
                }

                "prepareStatement" -> statement
                else -> null
            }
        }
    }

    private fun recordingStatement(now: Long): PreparedStatement {
        val resultSet = ownerResultSet(now)
        return jdbcProxy(PreparedStatement::class.java) { method, _ ->
            when (method.name) {
                "executeUpdate" -> 0 // not acquired
                "executeQuery" -> resultSet
                else -> null
            }
        }
    }

    private fun ownerResultSet(now: Long): ResultSet {
        // getLong is called with distinct column indexes, so the handler keys on the args
        return jdbcProxy(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> true
                "getLong" -> when (args?.get(0)) {
                    2 -> now + 10_000 // ttlAt: owner still in ttl
                    3 -> now + 16_000 // transitionAt: owner still in transition
                    else -> now // acquiredAt / currentAt
                }

                "getString" -> "other-owner"
                "getInt" -> 1
                else -> null
            }
        }
    }
}
