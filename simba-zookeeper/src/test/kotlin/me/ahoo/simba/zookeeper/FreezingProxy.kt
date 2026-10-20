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

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * A TCP proxy to `127.0.0.1:[target]` that stops forwarding on [freeze]: from then on it silently drops what either
 * side sends, on current and new connections, like a network partition that keeps sockets open.
 */
internal class FreezingProxy(private val target: Int) : AutoCloseable {
    private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var frozen: CountDownLatch? = null
    val port: Int = listener.localPort

    init {
        Thread {
            var client = runCatching { listener.accept() }.getOrNull()
            while (client != null) {
                connect(client)
                client = runCatching { listener.accept() }.getOrNull()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun connect(client: Socket) {
        val upstream = runCatching { Socket("127.0.0.1", target) }.getOrNull()
        if (upstream == null) {
            runCatching { client.close() }
            return
        }
        sockets += client
        sockets += upstream
        pump(client, upstream, fromClient = true)
        pump(upstream, client, fromClient = false)
    }

    /** Stops forwarding; the latch opens once the connecting side sent anything since. */
    fun freeze(): CountDownLatch = CountDownLatch(1).also { frozen = it }

    private fun pump(from: Socket, to: Socket, fromClient: Boolean) {
        Thread {
            val buffer = ByteArray(BUFFER_BYTES)
            runCatching {
                var read = from.getInputStream().read(buffer)
                while (read >= 0) {
                    val latch = frozen
                    if (latch == null) {
                        to.getOutputStream().write(buffer, 0, read)
                    } else if (fromClient) {
                        latch.countDown()
                    }
                    read = from.getInputStream().read(buffer)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val BUFFER_BYTES = 8192
    }
}
