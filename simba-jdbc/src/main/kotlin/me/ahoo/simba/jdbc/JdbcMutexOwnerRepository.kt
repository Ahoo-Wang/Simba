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
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import javax.sql.DataSource

/**
 * Jdbc Mutex Owner Repository.
 *
 * @author ahoo wang
 */
class JdbcMutexOwnerRepository(private val dataSource: DataSource) : MutexOwnerRepository {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcMutexOwnerRepository::class.java)
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

    @Throws(SQLException::class, SQLIntegrityConstraintViolationException::class)
    override fun initMutex(mutex: String): Boolean {
        require(mutex.isNotBlank()) { "mutex is blank!" }
        if (log.isInfoEnabled) {
            log.info("initMutex - mutex:[{}].", mutex)
        }
        dataSource.connection.use { connection -> return initMutex(connection, mutex) }
    }

    @Throws(SQLException::class)
    private fun initMutex(connection: Connection, mutex: String?): Boolean {
        connection.prepareStatement(SQL_INIT_MUTEX).use { initStatement ->
            initStatement.setString(1, mutex)
            val affected = initStatement.executeUpdate()
            return affected > 0
        }
    }

    override fun tryInitMutex(mutex: String): Boolean {
        return try {
            initMutex(mutex)
            true
        } catch (throwable: Throwable) {
            if (log.isInfoEnabled) {
                log.info("tryInitMutex failed.[{}]", throwable.message)
            }
            false
        }
    }

    override fun getOwner(mutex: String): MutexOwnerEntity {
        dataSource.connection.use { connection -> return getOwner(connection, mutex) }
    }

    @Throws(SQLException::class)
    private fun getOwner(connection: Connection, mutex: String): MutexOwnerEntity {
        connection.prepareStatement(SQL_GET).use { getStatement ->
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
                val entity = MutexOwnerEntity(mutex, ownerId, acquiredAt, ttlAt, transitionAt)
                entity.version = version
                entity.currentDbAt = currentAt
                return entity
            }
        }
    }

    override fun ensureOwner(mutex: String): MutexOwnerEntity {
        dataSource.connection.use { connection -> return ensureOwner(connection, mutex) }
    }

    @Throws(SQLException::class)
    private fun ensureOwner(connection: Connection, mutex: String): MutexOwnerEntity {
        return try {
            getOwner(connection, mutex)
        } catch (notFoundMutexOwnerException: NotFoundMutexOwnerException) {
            try {
                if (log.isInfoEnabled) {
                    log.info("ensureOwner - initMutex:[{}].", mutex)
                }
                initMutex(connection, mutex)
            } catch (sqlIntegrityConstraintViolationException: SQLException) {
                if (log.isWarnEnabled) {
                    log.warn(
                        sqlIntegrityConstraintViolationException.message,
                        sqlIntegrityConstraintViolationException
                    )
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
    override fun acquire(mutex: String, contenderId: String, ttl: Long, transition: Long): Boolean {
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
        connection.prepareStatement(SQL_ACQUIRE).use { acquireStatement ->
            acquireStatement.setLong(1, ttl)
            acquireStatement.setLong(2, ttl + transition)
            acquireStatement.setString(3, contenderId)
            acquireStatement.setString(4, mutex)
            acquireStatement.setString(5, contenderId)
            val affected = acquireStatement.executeUpdate()
            return affected > 0
        }
    }

    override fun acquireAndGetOwner(mutex: String, contenderId: String, ttl: Long, transition: Long): MutexOwnerEntity {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                return try {
                    var acquired = acquire(connection, mutex, contenderId, ttl, transition)
                    var mutexOwner = ensureOwner(connection, mutex)
                    if (!acquired && !mutexOwner.hasOwner()) {
                        /**
                         * 没有竞争到领导权 && 当前不存在领导者 ==> 初始化时
                         */
                        if (log.isInfoEnabled) {
                            log.info(
                                "acquireAndGetOwner - There is no competition for leadership && There is currently no leader [When initializing]. Retry!"
                            )
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
                    throw SimbaException(throwable.message!!, throwable)
                }
            }
        } catch (sqlException: SQLException) {
            throw SimbaException(sqlException.message!!, sqlException)
        }
    }

    override fun release(mutex: String, contenderId: String): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_RELEASE).use { initStatement ->
                initStatement.setString(1, mutex)
                initStatement.setString(2, contenderId)
                val affected = initStatement.executeUpdate()
                return affected > 0
            }
        }
    }
}
