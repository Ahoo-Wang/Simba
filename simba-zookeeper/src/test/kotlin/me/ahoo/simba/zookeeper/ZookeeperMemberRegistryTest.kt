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

import me.ahoo.simba.partition.MemberRegistry
import me.ahoo.simba.test.MemberRegistrySpec
import me.ahoo.test.asserts.assert
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ZookeeperMemberRegistryTest : MemberRegistrySpec() {
    private val testingServer = TestingServer().also { it.start() }
    private val curatorFramework: CuratorFramework =
        CuratorFrameworkFactory.newClient(testingServer.connectString, RetryNTimes(1, 10)).also { it.start() }
    override val memberRegistry: MemberRegistry = ZookeeperMemberRegistry(curatorFramework)
    override val expiresAfterTtl: Boolean = false

    @AfterAll
    fun close() {
        curatorFramework.close()
        testingServer.stop()
    }

    @Test
    fun `a closed session drops its memberships`() {
        CuratorFrameworkFactory.newClient(testingServer.connectString, RetryNTimes(1, 10)).use { other ->
            other.start()
            ZookeeperMemberRegistry(other).heartbeat("session", "gone", Duration.ofSeconds(1))
            memberRegistry.members("session").assert().containsExactly("gone")
        }

        memberRegistry.members("session").assert().isEmpty()
    }

    @Test
    fun `path separators are rejected`() {
        assertThrows<IllegalArgumentException> { memberRegistry.heartbeat("a/b", "m", Duration.ofSeconds(1)) }
        assertThrows<IllegalArgumentException> { memberRegistry.leave("g", "a/b") }
    }
}
