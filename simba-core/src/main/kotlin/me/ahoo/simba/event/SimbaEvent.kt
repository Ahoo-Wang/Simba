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

package me.ahoo.simba.event

import me.ahoo.simba.RevokeReason
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import java.time.Duration

/** Something the engine did, for metrics and logs. */
sealed interface SimbaEvent {
    data class SessionOpened(val session: SessionId) : SimbaEvent

    data class SessionRenewed(val session: SessionId, val latency: Duration) : SimbaEvent

    /** A keep-alive failed while the local deadline has not passed yet. */
    data class SessionSuspended(val session: SessionId, val cause: Throwable) : SimbaEvent

    data class SessionExpired(val session: SessionId, val reason: RevokeReason) : SimbaEvent

    data class Elected(val name: LeaseName, val token: Long) : SimbaEvent

    data class Revoked(val name: LeaseName, val token: Long, val reason: RevokeReason) : SimbaEvent

    data class AcquireFailed(val name: LeaseName, val cause: Throwable) : SimbaEvent
}

/** Receives [SimbaEvent]s; exceptions are logged and ignored. */
fun interface SimbaEventListener {
    fun onEvent(event: SimbaEvent)
}
