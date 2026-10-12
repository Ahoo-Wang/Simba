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
package me.ahoo.simba.test

import java.util.concurrent.ConcurrentHashMap

/**
 * The in-process recipe from the correctness guide: check and write under one lock.
 */
class InProcessFencingTest : FencingSpec() {
    private class Resource {
        var highestToken = 0L
    }

    private val resources = ConcurrentHashMap<String, Resource>()

    override fun write(resource: String, fencingToken: Long): Boolean {
        val state = resources.computeIfAbsent(resource) { Resource() }
        synchronized(state) {
            if (fencingToken < state.highestToken) {
                return false
            }
            state.highestToken = fencingToken
            // the protected write goes here, still under the lock
            return true
        }
    }

    override fun highestToken(resource: String): Long = resources[resource]?.highestToken ?: 0
}
