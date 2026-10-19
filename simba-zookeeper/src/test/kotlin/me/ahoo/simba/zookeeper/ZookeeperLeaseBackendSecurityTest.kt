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

import me.ahoo.simba.SimbaException
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.apache.curator.framework.AuthInfo
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.client.ZKClientConfig
import org.apache.zookeeper.data.ACL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [ZookeeperLeaseBackend] with digest authentication and creator-only ACLs. */
class ZookeeperLeaseBackendSecurityTest {
    private val lease = Duration.ofSeconds(30)
    private lateinit var server: TestingServer
    private lateinit var backend: ZookeeperLeaseBackend
    private lateinit var anonymous: ZooKeeper

    @BeforeEach
    fun setup() {
        server = TestingServer()
        backend = secured(SIMBA)
        anonymous = connect()
    }

    @AfterEach
    fun destroy() {
        anonymous.close()
        backend.close()
        server.close()
    }

    @Test
    fun anUnauthenticatedClientCannotDeleteOrPreCreateLeaseNodes() {
        val session = backend.openSession(lease).id
        val held = name()
        val token = backend.granted(session, held)

        assertThrows<KeeperException.NoAuthException> { anonymous.delete(leasePath(held), -1) }
        assertThrows<KeeperException.NoAuthException> {
            anonymous.create(leasePath(name()), ByteArray(0), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL)
        }
        assertThrows<KeeperException.NoAuthException> { anonymous.getChildren("/simba/leases", false) }
        assertThrows<KeeperException.NoAuthException> { anonymous.exists(leasePath(held), false) }
        backend.acquire(backend.openSession(lease).id, setOf(held)).assert()
            .isEqualTo(mapOf(held to AcquireResult.HeldBy(session, token)))
    }

    @Test
    fun anUnauthenticatedClientCannotForgeGroupMembers() {
        val session = backend.openSession(lease).id
        val group = "group-${UUID.randomUUID()}"
        backend.join(session, group)

        assertThrows<KeeperException.NoAuthException> {
            anonymous.create(
                "/simba/groups/$group/${SessionId.random()}",
                ByteArray(0),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL,
            )
        }
        backend.members(group).assert().containsExactly(session)
    }

    @Test
    fun anUnauthenticatedClientCannotDeleteGroupMembers() {
        val session = backend.openSession(lease).id
        val group = "group-${UUID.randomUUID()}"
        backend.join(session, group)

        assertThrows<KeeperException.NoAuthException> { anonymous.delete("/simba/groups/$group/$session", -1) }
        backend.members(group).assert().containsExactly(session)
    }

    @Test
    fun aMissingAncestorUnderAParentTheBackendMayNotCreateInIsRefused() {
        anonymous.setACL("/", listOf(ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE)), -1)
        val session = backend.openSession(lease).id

        assertThrows<KeeperException.NoAuthException> { backend.join(session, "group-${UUID.randomUUID()}") }
        assertThrows<KeeperException.NoAuthException> { backend.acquire(session, setOf(name())) }
    }

    @Test
    fun existingAncestorsUnderAParentTheBackendMayNotCreateInAreKept() {
        anonymous.create("/simba", ByteArray(0), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
        anonymous.create("/simba/groups", ByteArray(0), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
        anonymous.setACL("/", listOf(ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE)), -1)
        val session = backend.openSession(lease).id
        val group = "group-${UUID.randomUUID()}"

        // Both the missing group container and the missing leases parent go through /simba, which exists.
        backend.join(session, group)
        backend.granted(session, name())

        backend.members(group).assert().containsExactly(session)
    }

    @Test
    fun backendsWithTheSameCredentialsShareLeasesAndGroups() {
        val session = backend.openSession(lease).id
        val name = name()
        val token = backend.granted(session, name)
        val group = "group-${UUID.randomUUID()}"
        backend.join(session, group)

        secured(SIMBA).use { peer ->
            val other = peer.openSession(lease).id
            peer.acquire(other, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(session, token)))
            peer.granted(other, name())
            peer.join(other, group)

            backend.members(group).assert().isEqualTo(listOf(session, other).sortedBy { it.value })
        }
    }

    @Test
    fun otherCredentialsCanNeitherAcquireNorReadMembers() {
        val group = "group-${UUID.randomUUID()}"
        backend.join(backend.openSession(lease).id, group)
        backend.granted(backend.openSession(lease).id, name())

        secured("intruder:secret").use { intruder ->
            val session = intruder.openSession(lease).id
            assertThrows<KeeperException.NoAuthException> { intruder.acquire(session, setOf(name())) }
            assertThrows<KeeperException.NoAuthException> { intruder.members(group) }
        }
    }

    @Test
    fun everySessionClientUsesTheClientConfig() {
        val config = ZKClientConfig()
        ZookeeperLeaseBackend(server.connectString, clientConfig = config).use { configured ->
            val session = configured.openSession(lease).id

            checkNotNull(configured.zooKeeperOf(session)).clientConfig.assert().isSameAs(config)
        }
    }

    @Test
    fun aProviderWithoutAnAclForAPathFallsBackToItsDefault() {
        ZookeeperLeaseBackend(
            server.connectString,
            root = "/default-acl",
            authInfo = listOf(AuthInfo("digest", SIMBA.toByteArray())),
            aclProvider = DefaultOnly,
        ).use { fallback ->
            fallback.granted(fallback.openSession(lease).id, name())

            assertThrows<KeeperException.NoAuthException> { anonymous.getChildren("/default-acl/leases", false) }
        }
    }

    @Test
    fun membersFailsWhenTheGroupPathCannotBeSynced() {
        val stopped = TestingServer().apply { stop() }
        try {
            ZookeeperLeaseBackend(
                stopped.connectString,
                connectionTimeout = Duration.ofMillis(200)
            ).use { unreachable ->
                val error = assertThrows<Exception> { unreachable.members("group") }
                (error is SimbaException || error is KeeperException).assert().isTrue()
            }
        } finally {
            stopped.close()
        }
    }

    private fun secured(credentials: String): ZookeeperLeaseBackend = ZookeeperLeaseBackend(
        server.connectString,
        authInfo = listOf(AuthInfo("digest", credentials.toByteArray())),
        aclProvider = CreatorOnly,
    )

    private fun name(): LeaseName = LeaseName("zk.${UUID.randomUUID()}")

    private fun leasePath(name: LeaseName): String = "/simba/leases/$name"

    private fun ZookeeperLeaseBackend.granted(session: SessionId, name: LeaseName): Long {
        val result = acquire(session, setOf(name)).getValue(name)
        result.assert().isInstanceOf(AcquireResult.Granted::class.java)
        return (result as AcquireResult.Granted).token
    }

    private fun connect(): ZooKeeper {
        val connected = CountDownLatch(1)
        val zooKeeper = ZooKeeper(server.connectString, 30_000) {
            if (it.state == Watcher.Event.KeeperState.SyncConnected) connected.countDown()
        }
        connected.await(30, TimeUnit.SECONDS).assert().isTrue()
        return zooKeeper
    }

    /** Answers no per-path ACL, so the backend uses the default one. */
    private object DefaultOnly : ACLProvider {
        override fun getDefaultAcl(): List<ACL> = ZooDefs.Ids.CREATOR_ALL_ACL

        override fun getAclForPath(path: String): List<ACL>? = null
    }

    private object CreatorOnly : ACLProvider {
        override fun getDefaultAcl(): List<ACL> = ZooDefs.Ids.CREATOR_ALL_ACL

        override fun getAclForPath(path: String): List<ACL> = ZooDefs.Ids.CREATOR_ALL_ACL
    }

    companion object {
        private const val SIMBA = "simba:secret"
    }
}
