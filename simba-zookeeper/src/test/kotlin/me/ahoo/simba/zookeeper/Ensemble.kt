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

package me.ahoo.simba.zookeeper

import org.apache.curator.test.InstanceSpec
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Client, election and quorum ports for [servers] ensemble members, all distinct: Curator picks each port of an
 * [InstanceSpec] on its own, so two of one server can collide ("Client and election port must be different!").
 *
 * The ports stay bound until [release], so other sockets opened meanwhile (proxies) cannot take one of them.
 */
internal class Ensemble(servers: Int, tickTime: Int = -1) : AutoCloseable {
    private val reserved = List(servers * PORTS_PER_SERVER) {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
    }

    val specs: List<InstanceSpec> = List(servers) { index ->
        val ports = reserved.subList(index * PORTS_PER_SERVER, (index + 1) * PORTS_PER_SERVER).map { it.localPort }
        InstanceSpec(null, ports[0], ports[1], ports[2], true, index + 1, tickTime, -1)
    }

    /** Frees the ports for the servers; call right before starting them. */
    fun release() {
        reserved.forEach(ServerSocket::close)
    }

    override fun close() = release()

    private companion object {
        const val PORTS_PER_SERVER = 3
    }
}
