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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.Capabilities
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import java.net.URLEncoder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * [LeaseBackend] over the `simba_session`, `simba_lease` and `simba_member` tables (ADR 0010 §7.1), with the SQL
 * that differs between databases in [dialect].
 *
 * Every time decision uses the database clock: a session is live while `expires_at > now`, a lease belongs to the
 * session in its `session_id` only while that session is live, and remaining durations are the requested lease,
 * measured by the database from the statement that set `expires_at`. One keep-alive statement extends a session
 * together with all its leases and memberships.
 *
 * Lease rows are never deleted, so a name's token never resets. Expired sessions are cleaned up lazily: a lease of an
 * expired session is free for the next acquirer, a keep-alive that finds its session expired removes it, and
 * [purgeExpired] removes the rest when called.
 *
 * @param queryTimeout per-statement timeout; zero waits indefinitely.
 */
class JdbcLeaseBackend @JvmOverloads constructor(
    private val dataSource: DataSource,
    val dialect: Dialect,
    queryTimeout: Duration = Duration.ZERO,
) : LeaseBackend {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val MAX_BATCH = 64
        private const val MAX_ATTEMPTS = 3
        private const val MAX_GROUP_LENGTH = 128
        private const val MAX_METADATA_LENGTH = 1024
        private const val FREE = ""

        /** A backend on MySQL 8. */
        @JvmStatic
        @JvmOverloads
        fun mysql(dataSource: DataSource, queryTimeout: Duration = Duration.ZERO): JdbcLeaseBackend =
            JdbcLeaseBackend(dataSource, Dialect.MYSQL, queryTimeout)

        /** A backend on PostgreSQL 14+. */
        @JvmStatic
        @JvmOverloads
        fun postgres(dataSource: DataSource, queryTimeout: Duration = Duration.ZERO): JdbcLeaseBackend =
            JdbcLeaseBackend(dataSource, Dialect.POSTGRESQL, queryTimeout)
    }

    private val queryTimeoutSeconds: Int = run {
        require(!queryTimeout.isNegative) { "queryTimeout must not be negative: $queryTimeout" }
        (queryTimeout.seconds + if (queryTimeout.nano > 0) 1 else 0).toInt()
    }

    private val now = dialect.now
    private val sqlOpen = "insert into simba_session (session_id, expires_at, metadata) values (?, $now + ?, ?)"
    private val sqlKeepAlive =
        "update simba_session set expires_at = $now + ? where session_id = ? and expires_at > $now"
    private val sqlLive = "select ${dialect.live("?")}"
    private val sqlOwned = "select name, token from simba_lease where session_id = ?"
    private val sqlRelease = "update simba_lease set session_id = '$FREE' where name = ? and session_id = ? and token = ?"
    private val sqlFreeLeases = "update simba_lease set session_id = '$FREE' where session_id = ?"
    private val sqlDeleteMembers = "delete from simba_member where session_id = ?"
    private val sqlDeleteSession = "delete from simba_session where session_id = ?"
    private val sqlLeave = "delete from simba_member where group_name = ? and session_id = ?"
    private val sqlMembers =
        """
        select m.session_id from simba_member m
          join simba_session s on s.session_id = m.session_id
         where m.group_name = ? and s.expires_at > $now
         order by m.session_id
        """.trimIndent()
    private val sqlNow = "select $now"
    private val sqlPurgeLeases =
        "update simba_lease set session_id = '$FREE' where session_id in " +
            "(select session_id from simba_session where expires_at <= ?)"
    private val sqlPurgeMembers =
        "delete from simba_member where session_id in (select session_id from simba_session where expires_at <= ?)"
    private val sqlPurgeSessions = "delete from simba_session where expires_at <= ?"

    override val capabilities: Capabilities = Capabilities(maxBatch = MAX_BATCH)

    override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant {
        val millis = lease.requirePositiveMillis()
        val encoded = metadata.entries.joinToString("&") { (key, value) -> "${key.urlEncoded()}=${value.urlEncoded()}" }
        require(encoded.length <= MAX_METADATA_LENGTH) {
            "Encoded session metadata must be at most $MAX_METADATA_LENGTH characters, was ${encoded.length}."
        }
        val id = SessionId.random()
        autoCommit { connection ->
            connection.prepare(sqlOpen).use {
                it.setString(1, id.value)
                it.setLong(2, millis)
                it.setString(3, encoded)
                it.executeUpdate()
            }
        }
        log.debug { "Opened session [$id] on ${dialect.name} for $lease." }
        return SessionGrant(id, lease)
    }

    override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult {
        val millis = lease.requirePositiveMillis()
        return autoCommit { connection ->
            val renewed = connection.prepare(sqlKeepAlive).use {
                it.setLong(1, millis)
                it.setString(2, session.value)
                it.executeUpdate()
            }
            if (renewed == 0) {
                // Expiry is final: the session was not renewed while live, and no statement extends it afterwards.
                connection.end(session)
                log.debug { "Session [$session] expired on ${dialect.name}." }
                return@autoCommit KeepAliveResult.Expired
            }
            KeepAliveResult.Live(lease, connection.owned(session))
        }
    }

    override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> {
        require(names.size <= MAX_BATCH) { "At most $MAX_BATCH names per acquire, was ${names.size}." }
        return autoCommit { connection ->
            if (!connection.isLive(session)) {
                throw SessionExpiredException(session)
            }
            val results = LinkedHashMap<LeaseName, AcquireResult>()
            var pending: Set<LeaseName> = names
            repeat(MAX_ATTEMPTS) {
                pending.forEach { connection.tryAcquire(session, it) }
                val leases = connection.leases(pending)
                pending = pending.filterTo(LinkedHashSet()) { name ->
                    val lease = leases[name] ?: return@filterTo true
                    when {
                        lease.session == session.value -> results[name] = AcquireResult.Granted(lease.token)
                        lease.holderLive -> results[name] = AcquireResult.HeldBy(SessionId(lease.session), lease.token)
                        // The holder expired after the take-over attempt, or this session did: try again.
                        else -> return@filterTo true
                    }
                    false
                }
                if (pending.isEmpty()) {
                    return@autoCommit names.associateWith { results.getValue(it) }
                }
                if (!connection.isLive(session)) {
                    throw SessionExpiredException(session)
                }
            }
            throw SQLException("Leases $pending of session [$session] stayed unresolved after $MAX_ATTEMPTS attempts.")
        }
    }

    override fun release(session: SessionId, leases: Map<LeaseName, Long>) {
        if (leases.isEmpty()) {
            return
        }
        autoCommit { connection ->
            connection.prepare(sqlRelease).use {
                for ((name, token) in leases) {
                    it.setString(1, name.value)
                    it.setString(2, session.value)
                    it.setLong(3, token)
                    it.addBatch()
                }
                it.executeBatch()
            }
        }
    }

    override fun closeSession(session: SessionId) {
        transaction { connection -> connection.end(session) }
        log.debug { "Closed session [$session] on ${dialect.name}." }
    }

    override fun join(session: SessionId, group: String) {
        requireGroup(group)
        autoCommit { connection ->
            val inserted = connection.retrying {
                connection.prepare(dialect.join).use {
                    it.setString(1, group)
                    it.setString(2, session.value)
                    it.executeUpdate()
                }
            }
            if (inserted == 0 && !connection.isLive(session)) {
                throw SessionExpiredException(session)
            }
        }
    }

    override fun leave(session: SessionId, group: String) {
        autoCommit { connection ->
            connection.prepare(sqlLeave).use {
                it.setString(1, group)
                it.setString(2, session.value)
                it.executeUpdate()
            }
        }
    }

    override fun members(group: String): List<SessionId> =
        autoCommit { connection ->
            connection.prepare(sqlMembers).use {
                it.setString(1, group)
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(SessionId(rows.getString(1).trimEnd()))
                        }
                    }
                }
            }
        }

    /**
     * Removes every session expired by now, freeing its leases (tokens kept) and memberships. Not needed for
     * correctness; call it from time to time to keep the tables small. Answers the number of sessions removed.
     */
    fun purgeExpired(): Int =
        transaction { connection ->
            val cutoff = connection.prepare(sqlNow).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
            // Expiry is final, so the sessions expired by the cutoff stay expired across the three statements.
            listOf(sqlPurgeLeases, sqlPurgeMembers, sqlPurgeSessions).map { sql ->
                connection.prepare(sql).use {
                    it.setLong(1, cutoff)
                    it.executeUpdate()
                }
            }.last()
        }

    private class Lease(val session: String, val token: Long, val holderLive: Boolean)

    private fun Connection.tryAcquire(session: SessionId, name: LeaseName) {
        retrying {
            prepare(dialect.acquire).use {
                it.setString(1, name.value)
                it.setString(2, session.value)
                it.executeUpdate()
            }
        }
    }

    private fun Connection.leases(names: Set<LeaseName>): Map<LeaseName, Lease> {
        if (names.isEmpty()) {
            return emptyMap()
        }
        val placeholders = names.joinToString(", ") { "?" }
        val sql = "select l.name, l.session_id, l.token, ${dialect.live("l.session_id")} " +
            "from simba_lease l where l.name in ($placeholders)"
        return prepare(sql).use { statement ->
            names.forEachIndexed { index, name -> statement.setString(index + 1, name.value) }
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            LeaseName(rows.getString(1)),
                            Lease(rows.getString(2).trimEnd(), rows.getLong(3), rows.getBoolean(4))
                        )
                    }
                }
            }
        }
    }

    private fun Connection.isLive(session: SessionId): Boolean =
        prepare(sqlLive).use {
            it.setString(1, session.value)
            it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun Connection.owned(session: SessionId): Map<LeaseName, Long> =
        prepare(sqlOwned).use {
            it.setString(1, session.value)
            it.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(LeaseName(rows.getString(1)), rows.getLong(2))
                    }
                }
            }
        }

    /** Frees the leases and memberships of [session] and deletes it. */
    private fun Connection.end(session: SessionId) {
        listOf(sqlFreeLeases, sqlDeleteMembers, sqlDeleteSession).forEach { sql ->
            prepare(sql).use {
                it.setString(1, session.value)
                it.executeUpdate()
            }
        }
    }

    private fun <T> Connection.retrying(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (error: SQLException) {
                if (attempt >= MAX_ATTEMPTS || !dialect.isRetryable(error)) {
                    throw error
                }
                log.debug(error) { "Retrying a statement on ${dialect.name} after a transient conflict." }
                attempt++
            }
        }
    }

    private fun Connection.prepare(sql: String): PreparedStatement =
        prepareStatement(sql).also { it.queryTimeout = queryTimeoutSeconds }

    private fun <T> autoCommit(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            val previous = connection.autoCommit
            if (!previous) {
                connection.autoCommit = true
            }
            try {
                block(connection)
            } finally {
                if (!previous) {
                    connection.autoCommit = false
                }
            }
        }

    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            val previous = connection.autoCommit
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previous
            }
        }

    private fun Duration.requirePositiveMillis(): Long {
        val millis = toMillis()
        require(millis > 0) { "lease must be at least 1 ms: $this" }
        return millis
    }

    private fun requireGroup(group: String) {
        require(group.length in 1..MAX_GROUP_LENGTH) {
            "Group name length must be in [1, $MAX_GROUP_LENGTH], was ${group.length}."
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)
}
