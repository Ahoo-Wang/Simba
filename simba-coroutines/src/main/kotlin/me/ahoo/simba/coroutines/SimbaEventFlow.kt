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

package me.ahoo.simba.coroutines

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.SimbaEventListener

/**
 * A [SimbaEventListener] that republishes every [SimbaEvent] as a hot [Flow][kotlinx.coroutines.flow.Flow]: register
 * it with `Simba.builder().listener(...)` and collect [events]. Events reach only collectors subscribed when they are
 * published; a collector that falls more than [bufferCapacity] events behind loses the oldest ones, so the client's
 * event loop never waits for a collector.
 */
class SimbaEventFlow @JvmOverloads constructor(val bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY) :
    SimbaEventListener {
    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive, was $bufferCapacity." }
    }

    private val flow = MutableSharedFlow<SimbaEvent>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every event published after a collector subscribed, in order. */
    val events: SharedFlow<SimbaEvent> = flow.asSharedFlow()

    override fun onEvent(event: SimbaEvent) {
        flow.tryEmit(event)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 1024
    }
}
