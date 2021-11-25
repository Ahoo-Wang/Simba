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

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * @author ahoo wang
 */
public interface MutexOwnerRepository {

    boolean initMutex(String mutex) throws SQLException, SQLIntegrityConstraintViolationException;

    boolean tryInitMutex(String mutex);

    /**
     * @param mutex
     * @return when result is null throw {@link NotFoundMutexOwnerException}
     * @throws NotFoundMutexOwnerException not found mutex
     */
    MutexOwnerEntity getOwner(String mutex) throws NotFoundMutexOwnerException;

    boolean acquire(String mutex, String contenderId, long ttl, long transition);

    MutexOwnerEntity acquireAndGetOwner(String mutex, String contenderId, long ttl, long transition);

    boolean release(String mutex, String contenderId);

    MutexOwnerEntity ensureOwner(String mutex);
}
