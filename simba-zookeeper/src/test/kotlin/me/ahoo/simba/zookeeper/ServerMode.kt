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

import java.net.Socket

/** The `Mode` (`leader`, `follower`, `standalone`) of the server on [port], from `srvr` (enabled by default). */
internal fun serverMode(port: Int): String? = runCatching {
    Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().write("srvr".toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().decodeToString()
    }
}.getOrNull()?.lineSequence()?.firstOrNull { it.startsWith("Mode: ") }?.removePrefix("Mode: ")?.trim()
