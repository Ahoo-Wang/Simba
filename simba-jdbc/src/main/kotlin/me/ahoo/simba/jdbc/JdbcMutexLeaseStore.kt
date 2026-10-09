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

import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.MutexLeaseStore
import me.ahoo.simba.core.MutexOwner

/**
 * [MutexLeaseStore] backed by [MutexOwnerRepository]; acquire and renew share one database statement.
 *
 * @author ahoo wang
 */
internal class JdbcMutexLeaseStore(private val mutexOwnerRepository: MutexOwnerRepository) : MutexLeaseStore {
    override fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner {
        return mutexOwnerRepository.acquireAndGetOwner(mutex, contenderId, config.ttlMillis, config.transitionMillis)
    }

    override fun release(mutex: String, contenderId: String): Boolean {
        return mutexOwnerRepository.release(mutex, contenderId)
    }
}
