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

import me.ahoo.simba.spring.boot.starter.SimbaProperties
import me.ahoo.simba.zookeeper.ZookeeperLeaseBackend
import org.apache.curator.framework.AuthInfo
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.DefaultACLProvider
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `simba.zookeeper.*`: the ensemble that [ZookeeperLeaseBackend] opens one ZooKeeper session per Simba session on.
 *
 * @param root parent path of the lease and group nodes.
 * @param auth authentication every client adds; none when [Auth.credentials] is unset.
 * @param acl ACL of the nodes the backend creates; an `ACLProvider` bean takes precedence.
 */
@ConfigurationProperties(prefix = ZookeeperProperties.PREFIX)
data class ZookeeperProperties(
    val enabled: Boolean = true,
    val connectString: String = "localhost:2181",
    val root: String = ZookeeperLeaseBackend.DEFAULT_ROOT,
    val connectionTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECTION_TIMEOUT_SECONDS),
    val auth: Auth = Auth(),
    val acl: Acl = Acl.AUTO,
) {
    init {
        require(acl != Acl.CREATOR || auth.credentials != null) {
            "$PREFIX.acl=creator requires $PREFIX.auth.credentials."
        }
    }

    /**
     * Authentication of every Zookeeper client, e.g. scheme `digest` with credentials `user:password`.
     *
     * @param credentials the scheme's auth data (UTF-8); `null` for no authentication.
     */
    data class Auth(
        val scheme: String = "digest",
        val credentials: String? = null,
    ) {
        override fun toString(): String = "Auth(scheme=$scheme, credentials=${credentials?.let { "******" }})"
    }

    /** ACL of the nodes the backend creates. */
    enum class Acl {
        /** [CREATOR] when credentials are configured, otherwise [OPEN]. */
        AUTO,

        /** World-readable and writable (`OPEN_ACL_UNSAFE`): any client of the ensemble may change Simba's nodes. */
        OPEN,

        /** Only the authenticated identity that created a node may use it (`CREATOR_ALL_ACL`). */
        CREATOR,
    }

    /** The configured authentication, empty without credentials. */
    fun authInfo(): List<AuthInfo> =
        auth.credentials?.let { listOf(AuthInfo(auth.scheme, it.toByteArray(Charsets.UTF_8))) } ?: emptyList()

    /** The ACL provider [acl] selects. */
    fun aclProvider(): ACLProvider = when (acl) {
        Acl.OPEN -> DefaultACLProvider()
        Acl.CREATOR -> CreatorAclProvider
        Acl.AUTO -> if (auth.credentials == null) DefaultACLProvider() else CreatorAclProvider
    }

    private object CreatorAclProvider : ACLProvider {
        override fun getDefaultAcl(): List<ACL> = ZooDefs.Ids.CREATOR_ALL_ACL

        override fun getAclForPath(path: String): List<ACL> = ZooDefs.Ids.CREATOR_ALL_ACL
    }

    companion object {
        const val PREFIX = SimbaProperties.PREFIX + ".zookeeper"
        private const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 15L
    }
}
