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
package me.ahoo.simba.partition

import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * In-process mutex arbitration shared by every service of one factory: the first contender owns a mutex until it
 * stops, then the longest waiting contender takes over. Fencing tokens increase per term.
 */
class LocalMutexFactory(private val handleExecutor: Executor = ForkJoinPool.commonPool()) : MutexContendServiceFactory {
    private class MutexState {
        var owner: LocalService? = null
        val waiting = ArrayDeque<LocalService>()
        var token = 0L
    }

    private val mutexes = ConcurrentHashMap<String, MutexState>()

    /**
     * Mutexes whose next start fails once, e.g. to exercise error handling of the callers.
     */
    val failNextStart: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
        return LocalService(mutexContender)
    }

    private inner class LocalService(contender: MutexContender) :
        AbstractMutexContendService(contender, handleExecutor) {
        private val state: MutexState
            get() = mutexes.computeIfAbsent(mutex) { MutexState() }

        override fun startContend() {
            check(!failNextStart.remove(mutex)) { "start of $mutex failed" }
            val state = state
            synchronized(state) {
                if (state.owner == null) {
                    grant(state, this)
                } else {
                    state.waiting.addLast(this)
                }
            }
        }

        override fun stopContend() {
            val state = state
            synchronized(state) {
                state.waiting.remove(this)
                if (state.owner === this) {
                    state.owner = null
                    state.waiting.removeFirstOrNull()?.let { grant(state, it) }
                }
            }
        }

        private fun grant(state: MutexState, service: LocalService) {
            state.owner = service
            state.token++
            service.publish(MutexOwner(service.contenderId, fencingToken = state.token))
        }

        fun publish(owner: MutexOwner) {
            notifyOwner(owner)
        }

        override fun relinquish(cooldown: Duration) = throw UnsupportedOperationException()
    }
}

/**
 * [MemberRegistry] in memory without expiry: members stay until they leave (tests simulate a crash with [leave]).
 */
class LocalMemberRegistry : MemberRegistry {
    private val groups = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * While set, every call fails as an unreachable backend would.
     */
    @Volatile
    var failure: RuntimeException? = null

    override fun heartbeat(group: String, memberId: String, ttl: Duration) {
        failure?.let { throw it }
        groups.computeIfAbsent(group) { ConcurrentHashMap.newKeySet() }.add(memberId)
    }

    override fun leave(group: String, memberId: String) {
        failure?.let { throw it }
        groups[group]?.remove(memberId)
    }

    override fun members(group: String): List<String> = groups[group]?.sorted().orEmpty()
}
