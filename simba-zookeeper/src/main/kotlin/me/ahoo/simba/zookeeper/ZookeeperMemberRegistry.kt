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
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException
import java.time.Duration

/**
 * [MemberRegistry] on ephemeral nodes `/simba-members/{group}/{memberId}` (ADR 0009). A membership lives as long as
 * the Curator session, so `ttl` does not apply; a heartbeat recreates the node after a session expired.
 *
 * @author ahoo wang
 */
class ZookeeperMemberRegistry(private val curatorFramework: CuratorFramework) : MemberRegistry {
    companion object {
        const val RESOURCE_PREFIX = "/simba-members/"
    }

    private fun groupPath(group: String): String {
        require(group.isNotBlank() && '/' !in group) { "group must be a non-blank path segment: [$group]" }
        return RESOURCE_PREFIX + group
    }

    private fun memberPath(group: String, memberId: String): String {
        require(memberId.isNotBlank() && '/' !in memberId) { "memberId must be a non-blank path segment: [$memberId]" }
        return "${groupPath(group)}/$memberId"
    }

    override fun heartbeat(group: String, memberId: String, ttl: Duration) {
        val path = memberPath(group, memberId)
        if (curatorFramework.checkExists().forPath(path) != null) {
            return
        }
        try {
            curatorFramework.create().creatingParentContainersIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path)
        } catch (_: KeeperException.NodeExistsException) {
            // Created concurrently, by this or an older session of the same member; it expires with that session.
        }
    }

    override fun leave(group: String, memberId: String) {
        try {
            curatorFramework.delete().forPath(memberPath(group, memberId))
        } catch (_: KeeperException.NoNodeException) {
            // Not a member.
        }
    }

    override fun members(group: String): List<String> {
        return try {
            curatorFramework.children.forPath(groupPath(group)).sorted()
        } catch (_: KeeperException.NoNodeException) {
            emptyList()
        }
    }
}
