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

import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.tck.LeaseBackendSpec
import org.apache.curator.test.TestingServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

/** [ZookeeperLeaseBackend] on an embedded Zookeeper passes the backend TCK; its tokens are czxids. */
class ZookeeperLeaseBackendSpecTest : LeaseBackendSpec() {
    companion object {
        private lateinit var server: TestingServer

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = TestingServer()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.close()
        }
    }

    override fun createBackend(): LeaseBackend = ZookeeperLeaseBackend(server.connectString)
}
