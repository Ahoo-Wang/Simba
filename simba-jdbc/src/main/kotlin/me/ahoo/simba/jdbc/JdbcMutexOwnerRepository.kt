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
import me.ahoo.simba.SimbaException
import me.ahoo.simba.core.MutexOwner
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Duration
import javax.sql.DataSource

/**
 * Jdbc Mutex Owner Repository.
 *
 * @param queryTimeout per-statement timeout, rounded up to whole seconds (JDBC granularity);
 * [Duration.ZERO] means no limit. Bounds how long a hung database call blocks a contention.
 * @param fencing issue fencing tokens from the `fencing_token` column (ADR 0002). Requires that column; see
 * `init-script/upgrade-simba-mysql-fencing-token.sql` for existing tables.
 *
 * @author ahoo wang
 */
class JdbcMutexOwnerRepository @JvmOverloads constructor(
    private val dataSource: DataSource,
    queryTimeout: Duration = Duration.ZERO,
    private val fencing: Boolean = false
) : MutexOwnerRepository {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val SQL_INIT_MUTEX =
            """
                insert into simba_mutex 
                (mutex, acquired_at, ttl_at, transition_at, owner_id, version) 
                values 
                (?, 0, 0, 0, '', 0);
            """
        private const val SQL_GET =
            """
                select acquired_at, ttl_at, transition_at, owner_id, version, cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) as current_at 
                from simba_mutex 
                where mutex = ?;
            """
        private const val SQL_GET_FENCING =
            """
                select acquired_at, ttl_at, transition_at, owner_id, version, cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) as current_at, fencing_token
                from simba_mutex
                where mutex = ?;
            """

        /**
         * Advances `fencing_token` only when a new term starts (takeover, or re-acquire after the own lease ended).
         * It must be the first assignment: MySQL evaluates `SET` left to right with already-updated values, and the
         * condition has to read the previous `owner_id` / `transition_at`.
         */
        private const val SQL_ACQUIRE_FENCING =
            """
                update simba_mutex
                set fencing_token=if(owner_id = ? and transition_at > (unix_timestamp(current_timestamp(3)) * 1000), fencing_token, fencing_token + 1),
                acquired_at=cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned),
                ttl_at=(cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) + ?),
                transition_at=(cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) + ?),
                owner_id= ?,
                version=version + 1
                where mutex = ?
                and (
                    (transition_at < (unix_timestamp(current_timestamp(3)) * 1000))
                    or
                    (owner_id = ? and transition_at > (unix_timestamp(current_timestamp(3)) * 1000))
                );
            """
        private const val SQL_ACQUIRE =
            """
                update simba_mutex 
                set acquired_at=cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned),
                ttl_at=(cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) + ?),
                transition_at=(cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) + ?),
                owner_id= ?,
                version=version + 1 
                where mutex = ? 
                and (
                    (transition_at < (unix_timestamp(current_timestamp(3)) * 1000))
                    or
                    (owner_id = ? and transition_at > (unix_timestamp(current_timestamp(3)) * 1000))
                );
            """
        private const val SQL_RELEASE =
            """
                update simba_mutex 
                set acquired_at=0,
                ttl_at=0,
                transition_at=0,
                owner_id='',
                version=version + 1 
                where mutex = ? and owner_id = ?
            """
    }

    private val queryTimeoutSeconds: Int = queryTimeout.toQueryTimeoutSeconds()

    private fun Connection.prepare(sql: String): PreparedStatement {
        return prepareStatement(sql).also { it.queryTimeout = queryTimeoutSeconds }
    }

    @Throws(SQLException::class, SQLIntegrityConstraintViolationException::class)
    fun initMutex(mutex: String): Boolean {
        require(mutex.isNotBlank()) { "mutex is blank!" }
        log.info {
            "initMutex - mutex:[$mutex]."
        }
        dataSource.connection.use { connection -> return initMutex(connection, mutex) }
    }

    @Throws(SQLException::class)
    private fun initMutex(connection: Connection, mutex: String?): Boolean {
        connection.prepare(SQL_INIT_MUTEX).use { initStatement ->
            initStatement.setString(1, mutex)
            val affected = initStatement.executeUpdate()
            return affected > 0
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun tryInitMutex(mutex: String): Boolean {
        return try {
            initMutex(mutex)
            true
        } catch (throwable: Throwable) {
            log.error(throwable) {
                "tryInitMutex failed.[${throwable.message}]"
            }
            false
        }
    }

    fun getOwner(mutex: String): MutexOwnerEntity {
        dataSource.connection.use { connection -> return getOwner(connection, mutex) }
    }

    @Throws(SQLException::class)
    private fun getOwner(connection: Connection, mutex: String): MutexOwnerEntity {
        connection.prepare(if (fencing) SQL_GET_FENCING else SQL_GET).use { getStatement ->
            getStatement.setString(1, mutex)
            getStatement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    throw NotFoundMutexOwnerException(
                        "No mutex:[$mutex] is found, please initialize[MutexOwnerRepository.tryInitMutex] it first."
                    )
                }
                val acquiredAt = resultSet.getLong(1)
                val ttlAt = resultSet.getLong(2)
                val transitionAt = resultSet.getLong(3)
                val ownerId = resultSet.getString(4)
                val version = resultSet.getInt(5)
                val currentAt = resultSet.getLong(6)
                val fencingToken = if (fencing) resultSet.getLong(7) else MutexOwner.NO_FENCING_TOKEN
                val entity = MutexOwnerEntity(mutex, ownerId, acquiredAt, ttlAt, transitionAt, fencingToken)
                entity.version = version
                entity.currentDbAt = currentAt
                return entity
            }
        }
    }

    fun ensureOwner(mutex: String): MutexOwnerEntity {
        dataSource.connection.use { connection -> return ensureOwner(connection, mutex) }
    }

    @Suppress("SwallowedException")
    @Throws(SQLException::class)
    private fun ensureOwner(connection: Connection, mutex: String): MutexOwnerEntity {
        return try {
            getOwner(connection, mutex)
        } catch (notFoundMutexOwnerException: NotFoundMutexOwnerException) {
            try {
                log.info {
                    "ensureOwner - initMutex:[$mutex]."
                }
                initMutex(connection, mutex)
            } catch (sqlIntegrityConstraintViolationException: SQLIntegrityConstraintViolationException) {
                /*
                 * Only the unique-key race of concurrent initialization is tolerable;
                 * any other SQLException must propagate instead of being masked
                 * by the trailing getOwner's own failure.
                 */
                log.warn(sqlIntegrityConstraintViolationException) {
                    sqlIntegrityConstraintViolationException.message
                }
            }
            getOwner(connection, mutex)
        }
    }

    /**
     * acquire mutex.
     *
     * @param mutex mutex
     * @param contenderId contenderId
     * @param ttl [java.util.concurrent.TimeUnit.MILLISECONDS]
     * @param transition transition
     * @return if return true,acquired.
     */
    fun acquire(mutex: String, contenderId: String, ttl: Long, transition: Long): Boolean {
        dataSource.connection.use { return acquire(it, mutex, contenderId, ttl, transition) }
    }

    @Throws(SQLException::class)
    private fun acquire(
        connection: Connection,
        mutex: String,
        contenderId: String,
        ttl: Long,
        transition: Long
    ): Boolean {
        connection.prepare(if (fencing) SQL_ACQUIRE_FENCING else SQL_ACQUIRE).use { acquireStatement ->
            var index = 0
            if (fencing) {
                acquireStatement.setString(++index, contenderId)
            }
            acquireStatement.setLong(++index, ttl)
            acquireStatement.setLong(++index, ttl + transition)
            acquireStatement.setString(++index, contenderId)
            acquireStatement.setString(++index, mutex)
            acquireStatement.setString(++index, contenderId)
            val affected = acquireStatement.executeUpdate()
            return affected > 0
        }
    }

    override fun acquireAndGetOwner(mutex: String, contenderId: String, ttl: Long, transition: Long): MutexOwnerEntity {
        try {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    return try {
                        var acquired = acquire(connection, mutex, contenderId, ttl, transition)
                        var mutexOwner = ensureOwner(connection, mutex)
                        if (!acquired && !mutexOwner.hasOwner()) {
                            /**
                             * 没有竞争到领导权 && 当前不存在领导者 ==> 初始化时
                             */
                            log.info {
                                "acquireAndGetOwner - There is no competition for leadership && There is currently no leader [When initializing]. Retry!"
                            }
                            acquired = acquire(connection, mutex, contenderId, ttl, transition)
                            mutexOwner = ensureOwner(connection, mutex)
                        }
                        check(!(acquired && !mutexOwner.isOwner(contenderId))) {
                            /**
                             * 当前竞争者已竞争到领导权 && 最新 mutexOwner 不是当前竞争者
                             */
                            "Contender:[$contenderId] has acquired leadership, but MutexOwner status is inconsistent!"
                        }
                        connection.commit()
                        mutexOwner
                    } catch (throwable: Throwable) {
                        connection.rollback()
                        throw SimbaException(throwable)
                    }
                } finally {
                    /*
                     * Pools that do not reset autoCommit on return would silently roll back
                     * every later autoCommit-mode statement on the recycled connection.
                     */
                    connection.autoCommit = previousAutoCommit
                }
            }
        } catch (sqlException: SQLException) {
            throw SimbaException(sqlException)
        }
    }

    override fun release(mutex: String, contenderId: String): Boolean {
        dataSource.connection.use { connection ->
            connection.prepare(SQL_RELEASE).use { initStatement ->
                initStatement.setString(1, mutex)
                initStatement.setString(2, contenderId)
                val affected = initStatement.executeUpdate()
                return affected > 0
            }
        }
    }
}

private fun Duration.toQueryTimeoutSeconds(): Int {
    require(!isNegative) { "queryTimeout must not be negative: $this" }
    val seconds = if (nano > 0) seconds + 1 else seconds
    require(seconds <= Int.MAX_VALUE) { "queryTimeout must fit in Int seconds: $this" }
    return seconds.toInt()
}
