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
package me.ahoo.simba.spring.boot.starter.endpoint

import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.MutexContendService
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the running contend services of this process from their start and stop events.
 *
 * @author ahoo wang
 */
class SimbaServiceTracker : ContendObserver {
    private val services: MutableSet<MutexContendService> = ConcurrentHashMap.newKeySet()

    override fun onStarted(service: MutexContendService) {
        services.add(service)
    }

    override fun onStopped(service: MutexContendService) {
        services.remove(service)
    }

    /**
     * Running services ordered by mutex, then contender id.
     */
    fun services(): List<MutexContendService> {
        return services.sortedWith(compareBy({ it.mutex }, { it.contenderId }))
    }
}
