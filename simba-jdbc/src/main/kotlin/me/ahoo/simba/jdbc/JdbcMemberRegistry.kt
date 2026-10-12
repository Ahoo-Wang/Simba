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

import me.ahoo.simba.SimbaException
import me.ahoo.simba.partition.MemberRegistry
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * [MemberRegistry] on the `simba_member` table, expiring memberships by database time (ADR 0009).
 *
 * @param queryTimeout per-statement timeout; zero waits indefinitely.
 *
 * @author ahoo wang
 */
class JdbcMemberRegistry @JvmOverloads constructor(
    private val dataSource: DataSource,
    queryTimeout: Duration = Duration.ZERO
) : MemberRegistry {
    companion object {
        private const val NOW_MILLIS = "cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned)"
        private const val SQL_PRUNE = "delete from simba_member where group_name = ? and expire_at <= $NOW_MILLIS"
        private const val SQL_HEARTBEAT =
            """
                insert into simba_member (group_name, member_id, expire_at)
                values (?, ?, $NOW_MILLIS + ?)
                on duplicate key update expire_at = $NOW_MILLIS + ?
            """
        private const val SQL_LEAVE = "delete from simba_member where group_name = ? and member_id = ?"
        private const val SQL_MEMBERS =
            "select member_id from simba_member where group_name = ? and expire_at > $NOW_MILLIS order by member_id"
    }

    private val queryTimeoutSeconds: Int = run {
        require(!queryTimeout.isNegative) { "queryTimeout must not be negative: $queryTimeout" }
        (queryTimeout.seconds + if (queryTimeout.nano > 0) 1 else 0).toInt()
    }

    private fun Connection.prepare(sql: String): PreparedStatement {
        return prepareStatement(sql).also { it.queryTimeout = queryTimeoutSeconds }
    }

    override fun heartbeat(group: String, memberId: String, ttl: Duration) {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive: $ttl" }
        execute { connection ->
            connection.prepare(SQL_PRUNE).use {
                it.setString(1, group)
                it.executeUpdate()
            }
            connection.prepare(SQL_HEARTBEAT).use {
                it.setString(1, group)
                it.setString(2, memberId)
                it.setLong(3, ttl.toMillis())
                it.setLong(4, ttl.toMillis())
                it.executeUpdate()
            }
        }
    }

    override fun leave(group: String, memberId: String) {
        execute { connection ->
            connection.prepare(SQL_LEAVE).use {
                it.setString(1, group)
                it.setString(2, memberId)
                it.executeUpdate()
            }
        }
    }

    override fun members(group: String): List<String> {
        return execute { connection ->
            connection.prepare(SQL_MEMBERS).use { statement ->
                statement.setString(1, group)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString(1))
                        }
                    }
                }
            }
        }
    }

    private inline fun <T> execute(block: (Connection) -> T): T {
        try {
            return dataSource.connection.use(block)
        } catch (sqlException: SQLException) {
            throw SimbaException(sqlException)
        }
    }
}
