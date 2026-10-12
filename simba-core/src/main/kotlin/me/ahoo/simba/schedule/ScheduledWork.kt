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
package me.ahoo.simba.schedule

import me.ahoo.simba.core.LeaseExpiredException
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.checkLease
import java.time.Duration

/**
 * What scheduled work knows about the leadership term it runs in.
 *
 * @author ahoo wang
 */
interface ScheduleContext {
    val mutex: String

    /**
     * Fencing token of the current leadership term, to pass to protected resources.
     * [me.ahoo.simba.core.MutexOwner.NO_FENCING_TOKEN] when unsupported by the backend.
     */
    val fencingToken: Long

    /**
     * How long the current leadership term stays valid; see [MutexContendService.leaseRemaining]. Check it before
     * each step of long work instead of relying on interruption alone.
     */
    val leaseRemaining: Duration
        get() = Duration.ZERO

    /**
     * Throws [LeaseExpiredException] unless this node still leads with some lease left.
     */
    fun ensureLease() {
        ensureLease(Duration.ZERO)
    }

    /**
     * Throws [LeaseExpiredException] unless [leaseRemaining] exceeds [minRemaining], e.g. the expected duration of
     * the next step.
     */
    fun ensureLease(minRemaining: Duration) {
        checkLease(leaseRemaining, minRemaining) { "mutex:[$mutex]" }
    }
}

/**
 * Work that runs on the leader only.
 *
 * @author ahoo wang
 */
fun interface ScheduledWork {
    fun work(context: ScheduleContext)
}
