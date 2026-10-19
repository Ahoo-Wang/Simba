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

import java.sql.SQLException

/**
 * The SQL that differs between databases, for [JdbcLeaseBackend] over the schema in [initScript]
 * (`simba_session`, `simba_lease`, `simba_member`, ADR 0010 §7.1).
 *
 * Every statement reads time only through [now], so every lease decision uses the database clock.
 */
interface Dialect {
    /** Name of the database, for logs. */
    val name: String

    /** SQL expression: database time in epoch milliseconds, constant within one statement. */
    val now: String

    /** Classpath resource of the schema script. */
    val initScript: String

    /**
     * Inserts or takes over one lease. Parameters: `1` name, `2` session.
     *
     * Changes nothing unless the session is live. A missing lease is inserted with token `1`; an existing one moves to
     * the session with its token advanced by one only when its holder is another session that is not live (free, or
     * expired). Same session, or a live holder: unchanged.
     */
    val acquire: String

    /** Inserts one membership if absent, and nothing unless the session is live. Parameters: `1` group, `2` session. */
    val join: String

    /** Whether [error] is a transient conflict (deadlock, serialization failure) the statement may be retried after. */
    fun isRetryable(error: SQLException): Boolean = error.sqlState in RETRYABLE_SQL_STATES

    companion object {
        /** MySQL 8. */
        @JvmField
        val MYSQL: Dialect = MySqlDialect

        /** PostgreSQL 14+. */
        @JvmField
        val POSTGRESQL: Dialect = PostgreSqlDialect
    }
}

private val RETRYABLE_SQL_STATES = setOf("40001", "40P01")

/** Liveness of the session whose id is the expression [session], as of [now]. */
internal fun Dialect.live(session: String): String =
    "exists (select 1 from simba_session s where s.session_id = $session and s.expires_at > $now)"
