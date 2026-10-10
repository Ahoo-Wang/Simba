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

import me.ahoo.simba.core.MutexOwner

/**
 * Mutex Owner Repository.
 *
 * @author ahoo wang
 */
interface MutexOwnerRepository {
    /**
     * Acquires the lease (or renews it when held by [contenderId]) and returns the owner observed afterwards.
     *
     * @param ttl [java.util.concurrent.TimeUnit.MILLISECONDS]
     * @param transition [java.util.concurrent.TimeUnit.MILLISECONDS]
     */
    fun acquireAndGetOwner(mutex: String, contenderId: String, ttl: Long, transition: Long): MutexOwner

    /**
     * Releases the lease when held by [contenderId].
     */
    fun release(mutex: String, contenderId: String): Boolean
}
