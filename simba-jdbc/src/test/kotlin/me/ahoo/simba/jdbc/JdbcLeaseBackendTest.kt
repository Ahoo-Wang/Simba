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
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.abs

/** Behavior of [JdbcLeaseBackend] beyond the SPI contract, on every dialect. */
class JdbcLeaseBackendTest {
    companion object {
        private val LEASE: Duration = Duration.ofSeconds(30)
        private val TIMEOUT: Duration = Duration.ofSeconds(30)

        @JvmStatic
        fun backends(): List<JdbcLeaseBackend> =
            listOf(
                JdbcLeaseBackend.mysql(LeaseDatabaseFixture.mysql),
                JdbcLeaseBackend.mysql(LeaseDatabaseFixture.mysqlReadCommitted),
                JdbcLeaseBackend.postgres(LeaseDatabaseFixture.postgres),
            )
    }

    private val JdbcLeaseBackend.dataSource: DataSource
        get() = LeaseDatabaseFixture.dataSource(dialect)

    /**
     * Holds an uncommitted renewal of [session] (the committed row says expired) while [contender] runs on another
     * thread, waits until the contender blocks on the row lock, then commits the renewal and answers its result. A
     * contender reading the snapshot instead of the latest row would not block and the wait would time out.
     */
    private fun <T> JdbcLeaseBackend.raceWithRenewal(session: SessionId, contender: () -> T): T {
        dataSource.connection.use { connection ->
            connection.prepareStatement("update simba_session set expires_at = 0 where session_id = ?").use {
                it.setString(1, session.value)
                it.executeUpdate()
            }
        }
        dataSource.connection.use { renewal ->
            renewal.autoCommit = false
            try {
                renewal.prepareStatement(
                    "update simba_session set expires_at = ${dialect.now} + ? where session_id = ?"
                ).use {
                    it.setLong(1, LEASE.toMillis())
                    it.setString(2, session.value)
                    it.executeUpdate().assert().isEqualTo(1)
                }
                val attempt = CompletableFuture.supplyAsync(contender)
                val deadline = System.nanoTime() + TIMEOUT.toNanos()
                while (LeaseDatabaseFixture.lockWaits(dialect) == 0) {
                    check(!attempt.isDone) { "The contender did not wait for the renewal: ${attempt.join()}" }
                    check(System.nanoTime() - deadline < 0) { "The contender never waited for the row lock." }
                }
                renewal.commit()
                return attempt.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            } finally {
                renewal.rollback()
            }
        }
    }

    private fun name() = LeaseName("jdbc.${UUID.randomUUID()}")

    private fun <T> JdbcLeaseBackend.query(sql: String, vararg args: String, read: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, arg -> statement.setString(index + 1, arg) }
                statement.executeQuery().use(read)
            }
        }

    private fun JdbcLeaseBackend.count(table: String, session: SessionId): Int =
        query("select count(*) from $table where session_id = ?", session.value) {
            it.next()
            it.getInt(1)
        }

    private fun JdbcLeaseBackend.lease(name: LeaseName): Pair<String, Long> =
        query("select session_id, token from simba_lease where name = ?", name.value) {
            it.next()
            it.getString(1).trim() to it.getLong(2)
        }

    /** Opens a session that owns a lease and a membership, then expires it at the database. */
    private fun JdbcLeaseBackend.expiredSession(name: LeaseName): Pair<SessionId, Long> {
        val session = openSession(LEASE).id
        val token = (acquire(session, setOf(name)).getValue(name) as AcquireResult.Granted).token
        join(session, "jdbc.${UUID.randomUUID()}")
        dataSource.connection.use { connection ->
            connection.prepareStatement("update simba_session set expires_at = 0 where session_id = ?").use {
                it.setString(1, session.value)
                it.executeUpdate()
            }
        }
        return session to token
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `purge removes expired sessions and memberships and frees their leases keeping the token`(
        backend: JdbcLeaseBackend
    ) {
        val name = name()
        val (session, token) = backend.expiredSession(name)

        (backend.purgeExpired() >= 1).assert().isTrue()

        backend.count("simba_session", session).assert().isEqualTo(0)
        backend.count("simba_member", session).assert().isEqualTo(0)
        backend.lease(name).assert().isEqualTo("" to token)
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `a keep-alive that finds its session expired removes it and frees its leases keeping the token`(
        backend: JdbcLeaseBackend
    ) {
        val name = name()
        val (session, token) = backend.expiredSession(name)

        backend.keepAlive(session, LEASE, setOf(name)).assert().isEqualTo(KeepAliveResult.Expired)

        backend.count("simba_session", session).assert().isEqualTo(0)
        backend.count("simba_member", session).assert().isEqualTo(0)
        backend.lease(name).assert().isEqualTo("" to token)
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `a take-over serializes with a concurrent renewal of the holder`(backend: JdbcLeaseBackend) {
        val holder = backend.openSession(LEASE).id
        val contender = backend.openSession(LEASE).id
        val name = name()
        val token = (backend.acquire(holder, setOf(name)).getValue(name) as AcquireResult.Granted).token

        val result = backend.raceWithRenewal(holder) { backend.acquire(contender, setOf(name)) }

        result.assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(holder, token)))
        backend.keepAlive(holder, LEASE, setOf(name)).assert()
            .isEqualTo(KeepAliveResult.Live(LEASE, mapOf(name to token)))
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `a purge never removes a session whose renewal commits concurrently`(backend: JdbcLeaseBackend) {
        val holder = backend.openSession(LEASE).id
        val name = name()
        val token = (backend.acquire(holder, setOf(name)).getValue(name) as AcquireResult.Granted).token

        backend.raceWithRenewal(holder) { backend.purgeExpired() }

        backend.count("simba_session", holder).assert().isEqualTo(1)
        backend.lease(name).assert().isEqualTo(holder.value to token)
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `database time is UTC epoch milliseconds whatever the session time zone`(backend: JdbcLeaseBackend) {
        // MySQL in Docker has no zone tables, so it gets offsets; PostgreSQL gets zones far from UTC.
        val (setZone, zones) = if (backend.dialect == Dialect.MYSQL) {
            "set time_zone = ?" to listOf("+00:00", "-11:00", "+13:00")
        } else {
            "select set_config('TimeZone', ?, false)" to listOf("UTC", "Pacific/Pago_Pago", "Pacific/Kiritimati")
        }
        val readings = zones.map { zone ->
            backend.dataSource.connection.use { connection ->
                connection.prepareStatement(setZone).use {
                    it.setString(1, zone)
                    it.execute()
                }
                val before = System.currentTimeMillis()
                val now = connection.createStatement().use { statement ->
                    statement.executeQuery("select ${backend.dialect.now}").use {
                        it.next()
                        it.getLong(1)
                    }
                }
                now - before
            }
        }

        // The database and the JVM share a host clock in this test, so only zone-sized differences can show up.
        readings.forEach { (abs(it) < Duration.ofMinutes(1).toMillis()).assert().isTrue() }
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `session metadata is stored encoded and bounded`(backend: JdbcLeaseBackend) {
        val session = backend.openSession(LEASE, mapOf("host" to "node 1", "zone" to "a&b")).id
        try {
            backend.query("select metadata from simba_session where session_id = ?", session.value) {
                it.next()
                it.getString(1)
            }.assert().isEqualTo("host=node+1&zone=a%26b")
        } finally {
            backend.closeSession(session)
        }

        assertThrows<IllegalArgumentException> { backend.openSession(LEASE, mapOf("k" to "v".repeat(1024))) }
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `statements commit on a pool without auto-commit`(backend: JdbcLeaseBackend) {
        val source = backend.dataSource as HikariDataSource
        HikariDataSource().apply {
            jdbcUrl = source.jdbcUrl
            username = source.username
            password = source.password
            isAutoCommit = false
            maximumPoolSize = 1
        }.use { manual ->
            val manualBackend = JdbcLeaseBackend(manual, backend.dialect)
            val session = manualBackend.openSession(LEASE).id
            val name = name()

            manualBackend.acquire(session, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))
            backend.acquire(backend.openSession(LEASE).id, setOf(name)).assert()
                .isEqualTo(mapOf(name to AcquireResult.HeldBy(session, 1)))
            manualBackend.closeSession(session)
            manual.connection.use { it.autoCommit.assert().isFalse() }
        }
    }

    @ParameterizedTest
    @MethodSource("backends")
    fun `arguments are validated`(backend: JdbcLeaseBackend) {
        val session = SessionId.random()
        assertThrows<IllegalArgumentException> { backend.openSession(Duration.ZERO) }
        assertThrows<IllegalArgumentException> { backend.join(session, "") }
        assertThrows<IllegalArgumentException> {
            backend.acquire(session, (0..backend.capabilities.maxBatch).map { name() }.toSet())
        }
    }
}
