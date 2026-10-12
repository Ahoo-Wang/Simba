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

import me.ahoo.simba.SimbaException
import java.time.Duration

/**
 * Thrown by `ensureLease` when the local lease ends too soon to start the next step.
 *
 * @author ahoo wang
 */
class LeaseExpiredException(message: String) : SimbaException(message)

/**
 * Throws [LeaseExpiredException] unless [remaining] exceeds [minRemaining]; [subject] names the lease holder.
 */
internal fun checkLease(remaining: Duration, minRemaining: Duration, subject: () -> String) {
    if (remaining > minRemaining) {
        return
    }
    throw LeaseExpiredException(
        "${subject()} - lease remaining:[${remaining.toMillis()}ms] does not exceed [${minRemaining.toMillis()}ms]."
    )
}
