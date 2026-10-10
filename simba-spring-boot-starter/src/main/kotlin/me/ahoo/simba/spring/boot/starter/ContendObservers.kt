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
package me.ahoo.simba.spring.boot.starter

import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.WorkOutcome
import org.springframework.beans.factory.ObjectProvider

/**
 * Combines the application's [ContendObserver] beans into the single observer passed to a backend factory.
 *
 * @author ahoo wang
 */
internal object ContendObservers {
    fun of(observers: ObjectProvider<ContendObserver>): ContendObserver {
        val all = observers.orderedStream().toList()
        return when (all.size) {
            0 -> ContendObserver.NOOP
            1 -> all.single()
            else -> CompositeContendObserver(all)
        }
    }
}

/**
 * Forwards every event to each observer in order; one observer failing does not skip the others.
 */
internal class CompositeContendObserver(private val observers: List<ContendObserver>) : ContendObserver {
    private inline fun each(event: (ContendObserver) -> Unit) {
        var failure: RuntimeException? = null
        observers.forEach { observer ->
            try {
                event(observer)
            } catch (error: RuntimeException) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    override fun onContend(mutex: String, renew: Boolean, durationNanos: Long, outcome: ContendOutcome) =
        each { it.onContend(mutex, renew, durationNanos, outcome) }

    override fun onAcquired(mutex: String) = each { it.onAcquired(mutex) }

    override fun onReleased(mutex: String) = each { it.onReleased(mutex) }

    override fun onLeaseExpired(mutex: String) = each { it.onLeaseExpired(mutex) }

    override fun onWork(mutex: String, durationNanos: Long, outcome: WorkOutcome) =
        each { it.onWork(mutex, durationNanos, outcome) }
}
