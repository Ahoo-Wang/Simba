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

import me.ahoo.simba.Simba
import org.springframework.context.SmartLifecycle

/**
 * Closes the [Simba] client on shutdown: after the [me.ahoo.simba.spring.boot.starter.scheduling.SimbaScheduled]
 * work and the web server stopped (higher phases stop first), before infrastructure in lower phases such as the
 * connection factories its backend uses, so the session is closed while the backend is reachable and every lease is
 * freed at once. The client opens its session when it is built, so [start] only marks it running.
 */
class SimbaLifecycle(private val simba: Simba) : SmartLifecycle {
    companion object {
        /** Below the web server's graceful shutdown (`DEFAULT_PHASE - 1024`) and stop (`DEFAULT_PHASE - 2048`). */
        const val PHASE = SmartLifecycle.DEFAULT_PHASE - 4096
    }

    @Volatile
    private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        simba.close()
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = PHASE
}
