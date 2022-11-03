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

import java.util.concurrent.ThreadLocalRandom

/**
 * Contend Period.
 *
 * @author ahoo wang
 */
class ContendPeriod(private val contenderId: String) {
    fun ensureNextDelay(mutexOwner: MutexOwner): Long {
        val nextDelay = nextDelay(mutexOwner)
        return if (nextDelay < 0) 0 else nextDelay
    }

    fun nextDelay(mutexOwner: MutexOwner): Long {
        return if (mutexOwner.isOwner(contenderId)) {
            nextOwnerDelay(mutexOwner)
        } else nextContenderDelay(mutexOwner)
    }

    companion object {
        fun nextOwnerDelay(mutexOwner: MutexOwner): Long {
            return mutexOwner.ttlAt - System.currentTimeMillis()
        }

        @JvmStatic
        fun nextContenderDelay(mutexOwner: MutexOwner): Long {
            val transition = mutexOwner.transitionAt - mutexOwner.ttlAt
            val max: Long = 1000
            val min: Long = if (transition == 0L) 0 else -200
            val random = ThreadLocalRandom.current().nextLong(min, max)
            val now = System.currentTimeMillis()
            return mutexOwner.transitionAt - now + random
        }
    }
}
