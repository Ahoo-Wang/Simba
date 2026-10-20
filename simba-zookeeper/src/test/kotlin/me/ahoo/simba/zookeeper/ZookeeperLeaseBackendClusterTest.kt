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

import me.ahoo.test.asserts.assert
import org.apache.curator.test.TestingCluster
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** [ZookeeperLeaseBackend] on a three-server ensemble, where session clients and the reader may sit on different servers. */
class ZookeeperLeaseBackendClusterTest {
    private lateinit var cluster: TestingCluster
    private lateinit var backend: ZookeeperLeaseBackend

    @BeforeEach
    fun setup() {
        val ensemble = Ensemble(SERVERS)
        ensemble.release()
        cluster = TestingCluster(ensemble.specs).apply { start() }
        backend = ZookeeperLeaseBackend(cluster.connectString)
    }

    @AfterEach
    fun destroy() {
        backend.close()
        cluster.close()
    }

    /** [ZookeeperLeaseBackend.members] reads through the joining session's client, which orders its own writes. */
    @Test
    fun membersReadsTheJoinThatJustReturned() {
        val sessions = List(SESSIONS) { backend.openSession(Duration.ofSeconds(30)).id }

        repeat(ROUNDS) {
            val group = "group-${UUID.randomUUID()}"
            sessions.forEach { session ->
                backend.join(session, group)

                backend.members(group).assert().contains(session)
            }
        }
    }

    companion object {
        private const val SERVERS = 3
        private const val SESSIONS = 4
        private const val ROUNDS = 10
    }
}
