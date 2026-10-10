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

import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexOwner
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import java.time.Instant

/**
 * Read-only view of the mutexes this process contends for: `GET /actuator/simba` and `/actuator/simba/{mutex}`.
 *
 * @author ahoo wang
 */
@Endpoint(id = SimbaEndpoint.ID)
class SimbaEndpoint(private val tracker: SimbaServiceTracker) {
    companion object {
        const val ID = "simba"
    }

    @ReadOperation
    fun mutexes(): MutexesDescriptor {
        return MutexesDescriptor(tracker.services().map(::describe))
    }

    /**
     * The services contending for [mutex]; `null` (404) when this process does not contend for it.
     */
    @ReadOperation
    fun mutex(@Selector mutex: String): MutexesDescriptor? {
        val matching = tracker.services().filter { it.mutex == mutex }
        return if (matching.isEmpty()) null else MutexesDescriptor(matching.map(::describe))
    }

    private fun describe(service: MutexContendService): MutexDescriptor {
        val currentOwner = service.afterOwner
        return MutexDescriptor(
            mutex = service.mutex,
            contenderId = service.contenderId,
            status = service.status.name,
            owner = service.isOwner,
            currentOwner = if (currentOwner.hasOwner()) OwnerDescriptor.of(currentOwner) else null
        )
    }

    data class MutexesDescriptor(val mutexes: List<MutexDescriptor>)

    /**
     * @param owner whether this contender owns the mutex.
     * @param currentOwner the owner this contender last observed; `null` when it observed none.
     */
    data class MutexDescriptor(
        val mutex: String,
        val contenderId: String,
        val status: String,
        val owner: Boolean,
        val currentOwner: OwnerDescriptor?
    )

    /**
     * @param fencingToken `0` when the backend issues none.
     * @param ttlAt `null` for an unbounded lease, as are [transitionAt].
     */
    data class OwnerDescriptor(
        val ownerId: String,
        val fencingToken: Long,
        val acquiredAt: Instant,
        val ttlAt: Instant?,
        val transitionAt: Instant?
    ) {
        companion object {
            fun of(owner: MutexOwner): OwnerDescriptor {
                return OwnerDescriptor(
                    ownerId = owner.ownerId,
                    fencingToken = owner.fencingToken,
                    acquiredAt = Instant.ofEpochMilli(owner.acquiredAt),
                    ttlAt = owner.ttlAt.toInstantOrNull(),
                    transitionAt = owner.transitionAt.toInstantOrNull()
                )
            }

            private fun Long.toInstantOrNull(): Instant? {
                return if (this == Long.MAX_VALUE) null else Instant.ofEpochMilli(this)
            }
        }
    }
}
