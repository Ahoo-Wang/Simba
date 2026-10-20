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

package me.ahoo.simba.spring.boot.starter.zookeeper

import me.ahoo.simba.Simba
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.boot.starter.Leadership
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.zookeeper.ZookeeperLeaseBackend
import me.ahoo.test.asserts.assert
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.DefaultACLProvider
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimbaZookeeperAutoConfigurationTest {
    private val server = TestingServer()
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaZookeeperAutoConfiguration::class.java)
        )
        .withPropertyValues("simba.backend=zookeeper", "simba.zookeeper.connect-string=${server.connectString}")

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the client is elected on the zookeeper backend`() {
        contextRunner.run {
            assertThat(it).hasSingleBean(ZookeeperLeaseBackend::class.java)
            val leadership = Leadership()
            it.getBean(Simba::class.java).election("zookeeper-starter").start(leadership)
            (leadership.nextTerm().token > 0).assert().isTrue()
        }
    }

    @Test
    fun `opening a session waits at most the renew interval unless configured`() {
        contextRunner.withPropertyValues("simba.session.lease-duration=9s").run {
            it.getBean(ZookeeperLeaseBackend::class.java).connectionTimeout.assert().isEqualTo(Duration.ofSeconds(3))
        }
        contextRunner.withPropertyValues("simba.zookeeper.connection-timeout=2s").run {
            it.getBean(ZookeeperLeaseBackend::class.java).connectionTimeout.assert().isEqualTo(Duration.ofSeconds(2))
        }
    }

    @Test
    fun `selecting another backend leaves the zookeeper backend off`() {
        contextRunner.withPropertyValues("simba.backend=redis").run {
            assertThat(it).doesNotHaveBean(LeaseBackend::class.java).doesNotHaveBean(Simba::class.java)
        }
    }

    @Test
    fun `credentials authenticate the clients and restrict the nodes to their identity`() {
        val root = "/simba-${UUID.randomUUID()}"
        contextRunner.withPropertyValues(
            "simba.zookeeper.root=$root",
            "simba.zookeeper.auth.credentials=simba:secret",
        ).run {
            elect(it.getBean(Simba::class.java))

            anonymous { zooKeeper ->
                assertThrows<KeeperException.NoAuthException> { zooKeeper.getChildren("$root/leases", false) }
            }
        }
    }

    @Test
    fun `an ACLProvider bean replaces the configured acl`() {
        val root = "/simba-${UUID.randomUUID()}"
        contextRunner.withPropertyValues(
            "simba.zookeeper.root=$root",
            "simba.zookeeper.auth.credentials=simba:secret",
        ).withBean(ACLProvider::class.java, { DefaultACLProvider() }).run {
            elect(it.getBean(Simba::class.java))

            anonymous { zooKeeper -> zooKeeper.getChildren("$root/leases", false).assert().hasSize(1) }
        }
    }

    @Test
    fun `an open acl keeps the nodes world-readable despite credentials`() {
        val root = "/simba-${UUID.randomUUID()}"
        contextRunner.withPropertyValues(
            "simba.zookeeper.root=$root",
            "simba.zookeeper.auth.scheme=digest",
            "simba.zookeeper.auth.credentials=simba:secret",
            "simba.zookeeper.acl=open",
        ).run {
            it.getBean(ZookeeperProperties::class.java).acl.assert().isEqualTo(ZookeeperProperties.Acl.OPEN)
            elect(it.getBean(Simba::class.java))

            anonymous { zooKeeper -> zooKeeper.getChildren("$root/leases", false).assert().hasSize(1) }
        }
    }

    @Test
    fun `a creator acl with credentials restricts the nodes`() {
        val root = "/simba-${UUID.randomUUID()}"
        contextRunner.withPropertyValues(
            "simba.zookeeper.root=$root",
            "simba.zookeeper.auth.credentials=simba:secret",
            "simba.zookeeper.acl=creator",
        ).run {
            elect(it.getBean(Simba::class.java))

            anonymous { zooKeeper ->
                assertThrows<KeeperException.NoAuthException> { zooKeeper.getChildren("$root/leases", false) }
            }
        }
    }

    @Test
    fun `unset auth properties bind to no authentication and an open acl`() {
        contextRunner.run {
            val properties = it.getBean(ZookeeperProperties::class.java)
            properties.auth.assert().isEqualTo(ZookeeperProperties.Auth())
            properties.acl.assert().isEqualTo(ZookeeperProperties.Acl.AUTO)
            properties.authInfo().assert().isEmpty()
            properties.aclProvider().defaultAcl.assert().isEqualTo(ZooDefs.Ids.OPEN_ACL_UNSAFE)
        }
    }

    @Test
    fun `every acl mode selects its provider`() {
        val credentials = ZookeeperProperties.Auth(scheme = "digest", credentials = "simba:secret")
        val open = ZookeeperProperties(
            enabled = true,
            connectString = "zk:2181",
            root = "/simba",
            connectionTimeout = Duration.ofSeconds(1),
            auth = credentials,
            acl = ZookeeperProperties.Acl.OPEN,
        )
        val creator = open.copy(acl = ZookeeperProperties.Acl.CREATOR)

        open.aclProvider().defaultAcl.assert().isEqualTo(ZooDefs.Ids.OPEN_ACL_UNSAFE)
        creator.aclProvider().getAclForPath("/simba").assert().isEqualTo(ZooDefs.Ids.CREATOR_ALL_ACL)
        creator.authInfo().single().scheme.assert().isEqualTo("digest")
        ZookeeperProperties.Auth().toString().assert().isEqualTo("Auth(scheme=digest, credentials=null)")
        assertThrows<IllegalArgumentException> { ZookeeperProperties(acl = ZookeeperProperties.Acl.CREATOR) }
    }

    @Test
    fun `a creator acl without credentials fails startup`() {
        contextRunner.withPropertyValues("simba.zookeeper.acl=creator").run {
            assertThat(it).hasFailed()
        }
    }

    @Test
    fun `the credentials never show in the properties string`() {
        val properties = ZookeeperProperties(auth = ZookeeperProperties.Auth(credentials = "simba:secret"))

        properties.toString().assert().doesNotContain("secret")
        properties.aclProvider().defaultAcl.assert().isEqualTo(ZooDefs.Ids.CREATOR_ALL_ACL)
        ZookeeperProperties().aclProvider().defaultAcl
            .assert().isEqualTo(ZooDefs.Ids.OPEN_ACL_UNSAFE)
    }

    private fun elect(simba: Simba) {
        val leadership = Leadership()
        simba.election("zookeeper-auth").start(leadership)
        (leadership.nextTerm().token > 0).assert().isTrue()
    }

    private fun anonymous(block: (ZooKeeper) -> Unit) {
        val connected = CountDownLatch(1)
        val zooKeeper = ZooKeeper(server.connectString, 30_000) {
            if (it.state == Watcher.Event.KeeperState.SyncConnected) connected.countDown()
        }
        try {
            connected.await(30, TimeUnit.SECONDS).assert().isTrue()
            block(zooKeeper)
        } finally {
            zooKeeper.close()
        }
    }
}
