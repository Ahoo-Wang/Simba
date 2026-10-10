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
package me.ahoo.simba.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Host address used in contender ids: the last IPv4, non-loopback, non-wildcard address of an up, non-loopback
 * network interface, falling back to [InetAddress.getLocalHost] and then to `127.0.0.1`.
 *
 * @author ahoo wang
 */
internal object LocalHost {
    private val log = KotlinLogging.logger {}
    private const val LOOPBACK = "127.0.0.1"

    val hostAddress: String by lazy { detect() }

    @Suppress("TooGenericExceptionCaught")
    private fun detect(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .lastOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
                ?.hostAddress
        } catch (error: Exception) {
            log.warn(error) { "detect - cannot scan network interfaces." }
            null
        } ?: fallback()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fallback(): String {
        return try {
            InetAddress.getLocalHost().hostAddress
        } catch (error: Exception) {
            log.warn(error) { "fallback - cannot resolve the local host, using $LOOPBACK." }
            LOOPBACK
        }
    }
}
