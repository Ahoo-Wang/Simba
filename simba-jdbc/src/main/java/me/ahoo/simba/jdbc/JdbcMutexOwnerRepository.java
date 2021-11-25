/*
 * Copyright [2021-2021] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

package me.ahoo.simba.jdbc;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.SimbaException;

import javax.sql.DataSource;
import java.sql.*;

/**
 * @author ahoo wang
 */
@Slf4j
public class JdbcMutexOwnerRepository implements MutexOwnerRepository {

    private final DataSource dataSource;

    public JdbcMutexOwnerRepository(DataSource dataSource) {
        Preconditions.checkNotNull(dataSource, "dataSource can not be null!");
        this.dataSource = dataSource;
    }

    private static final String SQL_INIT_MUTEX = "insert into simba_mutex " +
            "(mutex, acquired_at, ttl_at, transition_at, owner_id, version) " +
            "values (?, 0, 0, 0, '', 0);";

    @Override
    public boolean initMutex(String mutex) throws SQLException, SQLIntegrityConstraintViolationException {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(mutex), "mutex can not be empty!");

        if (log.isInfoEnabled()) {
            log.info("initMutex - mutex:[{}].", mutex);
        }

        try (Connection connection = dataSource.getConnection()) {
            return initMutex(connection, mutex);
        }
    }

    private boolean initMutex(Connection connection, String mutex) throws SQLException {
        try (PreparedStatement initStatement = connection.prepareStatement(SQL_INIT_MUTEX)) {
            initStatement.setString(1, mutex);
            int affected = initStatement.executeUpdate();
            return affected > 0;
        }
    }

    @Override
    public boolean tryInitMutex(String mutex) {
        try {
            initMutex(mutex);
            return true;
        } catch (Throwable throwable) {
            if (log.isInfoEnabled()) {
                log.info("tryInitMutex failed.[{}]", throwable.getMessage());
            }
            return false;
        }
    }

    private final static String SQL_GET
            =
            "select acquired_at, ttl_at, transition_at, owner_id, version, cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) as current_at " +
                    "from simba_mutex " +
                    "where mutex = ?;";

    @Override
    public MutexOwnerEntity getOwner(String mutex) {
        try (Connection connection = dataSource.getConnection()) {
            return getOwner(connection, mutex);
        } catch (SQLException sqlException) {
            throw new SimbaException(sqlException.getMessage(), sqlException);
        }
    }

    private MutexOwnerEntity getOwner(Connection connection, String mutex) throws SQLException {
        try (PreparedStatement getStatement = connection.prepareStatement(SQL_GET)) {
            getStatement.setString(1, mutex);
            try (ResultSet resultSet = getStatement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new NotFoundMutexOwnerException(Strings.lenientFormat("No mutex:[%s] is found, please initialize[MutexOwnerRepository.tryInitMutex] it first.", mutex));
                }
                long acquiredAt = resultSet.getLong(1);
                long ttlAt = resultSet.getLong(2);
                long transitionAt = resultSet.getLong(3);
                String ownerId = resultSet.getString(4);
                int version = resultSet.getInt(5);
                long currentAt = resultSet.getLong(6);
                MutexOwnerEntity entity = new MutexOwnerEntity(mutex, ownerId, acquiredAt, ttlAt, transitionAt);
                entity.setVersion(version);
                entity.setCurrentDbAt(currentAt);
                return entity;
            }
        }
    }

    @Override
    public MutexOwnerEntity ensureOwner(String mutex) {
        try (Connection connection = dataSource.getConnection()) {
            return ensureOwner(connection, mutex);
        } catch (SQLException sqlException) {
            throw new SimbaException(sqlException.getMessage(), sqlException);
        }
    }

    private MutexOwnerEntity ensureOwner(Connection connection, String mutex) throws SQLException {
        try {
            return getOwner(connection, mutex);
        } catch (NotFoundMutexOwnerException notFoundMutexOwnerException) {
            try {
                if (log.isInfoEnabled()) {
                    log.info("ensureOwner - initMutex:[{}].", mutex);
                }
                initMutex(connection, mutex);
            } catch (SQLException sqlIntegrityConstraintViolationException) {
                if (log.isWarnEnabled()) {
                    log.warn(sqlIntegrityConstraintViolationException.getMessage(), sqlIntegrityConstraintViolationException);
                }
            }
            return getOwner(connection, mutex);
        }
    }

    private static final String SQL_ACQUIRE =
            "update simba_mutex " +
                    "set acquired_at=cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned)," +
                    /**
                     * 1:ttl
                     */
                    "    ttl_at=(cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) + ?)," +
                    /**
                     * 2:ttl+transition
                     */
                    "    transition_at=(cast(unix_timestamp(current_timestamp(3)) * 1000 as unsigned) + ?)," +
                    /**
                     * 3:contenderId
                     */
                    "    owner_id= ?," +
                    "    version=version + 1 " +
                    /**
                     * 4:mutex
                     */
                    "where mutex = ? " +
                    "  and (" +
                    "      (transition_at < (unix_timestamp(current_timestamp(3)) * 1000)) " +
                    "    or" +
                    /**
                     * 5.contenderId
                     */
                    "       (owner_id = ? and transition_at > (unix_timestamp(current_timestamp(3)) * 1000))" +
                    "    );";


    /**
     * @param mutex
     * @param contenderId
     * @param ttl         {@link java.util.concurrent.TimeUnit#MILLISECONDS}
     * @param transition
     * @return
     */
    @Override
    public boolean acquire(String mutex, String contenderId, long ttl, long transition) {
        try (Connection connection = dataSource.getConnection()) {
            return acquire(connection, mutex, contenderId, ttl, transition);
        } catch (SQLException sqlException) {
            throw new SimbaException(sqlException.getMessage(), sqlException);
        }
    }

    @Override
    public MutexOwnerEntity acquireAndGetOwner(String mutex, String contenderId, long ttl, long transition) {

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean acquired = acquire(connection, mutex, contenderId, ttl, transition);
                MutexOwnerEntity mutexOwner = ensureOwner(connection, mutex);

                if (!acquired && !mutexOwner.hasOwner()) {
                    /**
                     * 没有竞争到领导权 && 当前不存在领导者 ==> 初始化时
                     */
                    if (log.isInfoEnabled()) {
                        log.info("acquireAndGetOwner - There is no competition for leadership && There is currently no leader [When initializing]. Retry!");
                    }
                    acquired = acquire(connection, mutex, contenderId, ttl, transition);
                    mutexOwner = ensureOwner(connection, mutex);
                }
                if (acquired && !mutexOwner.isOwner(contenderId)) {
                    /**
                     * 当前竞争者已竞争到领导权 && 最新 mutexOwner 不是当前竞争者
                     */
                    throw new IllegalStateException(Strings.lenientFormat("Contender:[%s] has acquired leadership, but MutexOwner status is inconsistent!", contenderId));
                }
                connection.commit();
                return mutexOwner;
            } catch (Throwable throwable) {
                connection.rollback();
                throw new SimbaException(throwable.getMessage(), throwable);
            }
        } catch (SQLException sqlException) {
            throw new SimbaException(sqlException.getMessage(), sqlException);
        }
    }

    private boolean acquire(Connection connection, String mutex, String contenderId, long ttl, long transition) throws SQLException {
        try (PreparedStatement acquireStatement = connection.prepareStatement(SQL_ACQUIRE)) {
            acquireStatement.setLong(1, ttl);
            acquireStatement.setLong(2, ttl + transition);
            acquireStatement.setString(3, contenderId);
            acquireStatement.setString(4, mutex);
            acquireStatement.setString(5, contenderId);
            int affected = acquireStatement.executeUpdate();
            return affected > 0;
        }
    }

    private static final String SQL_RELEASE = "update simba_mutex set " +
            "acquired_at=0," +
            "ttl_at=0," +
            "transition_at=0," +
            "owner_id=''," +
            "version=version + 1 " +
            /**
             * 1:mutex,2:contenderId,
             */
            "where mutex = ? and owner_id = ?;";

    @Override
    public boolean release(String mutex, String contenderId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement initStatement = connection.prepareStatement(SQL_RELEASE)) {
            initStatement.setString(1, mutex);
            initStatement.setString(2, contenderId);
            int affected = initStatement.executeUpdate();
            return affected > 0;
        } catch (SQLException sqlException) {
            throw new SimbaException(sqlException.getMessage(), sqlException);
        }
    }
}
