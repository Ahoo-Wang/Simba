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

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Abstract Mutex Contender.
 *
 * @author ahoo wang
 */
abstract class AbstractMutexContender(
    final override val mutex: String,
    final override val contenderId: String = ContenderIdGenerator.HOST.generate()
) : MutexContender {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    init {
        require(mutex.isNotBlank()) { "mutex must not be blank!" }
        require(contenderId.isNotBlank()) { "contenderId must not be blank!" }
    }

    override fun onAcquired(mutexState: MutexState) {
        log.info {
            "onAcquired - mutex:[$mutex] - contenderId:[$contenderId]."
        }
    }

    override fun onReleased(mutexState: MutexState) {
        log.info {
            "onReleased - mutex:[$mutex] - contenderId:[$contenderId]."
        }
    }
}
