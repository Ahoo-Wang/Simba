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
package me.ahoo.simba.core

/**
 * Backend storage of mutex leases, driven by [LeaseContendService].
 *
 * Implementations perform one atomic backend operation per call and must not schedule or notify.
 *
 * @author ahoo wang
 */
interface MutexLeaseStore {
    /**
     * Acquires the lease, or renews it when [renew] is `true` (the caller believes it is the owner).
     *
     * @return the owner observed by the backend after the operation, [MutexOwner.NONE] when there is none.
     */
    fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner

    /**
     * Releases the lease when held by [contenderId].
     *
     * @return `true` when the lease was released by this call.
     */
    fun release(mutex: String, contenderId: String): Boolean
}
