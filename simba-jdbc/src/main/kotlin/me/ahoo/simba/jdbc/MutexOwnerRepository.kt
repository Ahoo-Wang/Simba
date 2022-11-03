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
import java.sql.SQLIntegrityConstraintViolationException

/**
 * Mutex Owner Repository.
 *
 * @author ahoo wang
 */
interface MutexOwnerRepository {
    @Throws(SQLException::class, SQLIntegrityConstraintViolationException::class)
    fun initMutex(mutex: String): Boolean
    fun tryInitMutex(mutex: String): Boolean

    /**
     * get Owner.
     *
     * @param mutex mutex
     * @return when result is null throw [NotFoundMutexOwnerException]
     * @throws NotFoundMutexOwnerException not found mutex
     */
    @Throws(NotFoundMutexOwnerException::class)
    fun getOwner(mutex: String): MutexOwnerEntity
    fun acquire(mutex: String, contenderId: String, ttl: Long, transition: Long): Boolean
    fun acquireAndGetOwner(mutex: String, contenderId: String, ttl: Long, transition: Long): MutexOwnerEntity
    fun release(mutex: String, contenderId: String): Boolean
    fun ensureOwner(mutex: String): MutexOwnerEntity
}
