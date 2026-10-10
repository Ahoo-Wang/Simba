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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.recipes.leader.LeaderLatch.CloseMode
import org.apache.curator.framework.recipes.leader.LeaderLatchListener
import java.util.concurrent.Executor

/**
 * Zookeeper Mutex Contend Service.
 *
 * @author ahoo wang
 */
class ZookeeperMutexContendService(
    contender: MutexContender,
    handleExecutor: Executor,
    private val curatorFramework: CuratorFramework
) : AbstractMutexContendService(contender, handleExecutor), LeaderLatchListener {

    @Volatile
    private var leaderLatch: LeaderLatch? = null
    private val mutexPath: String = RESOURCE_PREFIX + contender.mutex

    @Suppress("TooGenericExceptionCaught")
    override fun startContend() {
        val latch = LeaderLatch(curatorFramework, mutexPath, contenderId)
        latch.addListener(this)
        // Assigned before start(): isLeader() may fire during start() and reads the latch for the fencing token.
        leaderLatch = latch
        try {
            latch.start()
        } catch (error: Throwable) {
            // A latch that failed to start must not keep its listener or a half-created node.
            leaderLatch = null
            try {
                latch.close(CloseMode.SILENT)
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    override fun stopContend() {
        leaderLatch!!.close(CloseMode.NOTIFY_LEADER)
        leaderLatch = null
    }

    override fun isLeader() {
        if (!status.isActive) {
            /*
             * A late callback delivered after stop (INITIAL), or racing with stop (STOPPING),
             * must not revive ownership: notLeader() is still allowed during STOPPING because
             * CloseMode.NOTIFY_LEADER delivers the release notification while stopping.
             */
            return
        }
        notifyOwner(MutexOwner(contenderId, fencingToken = leadershipFencingToken()))
    }

    /**
     * The czxid of the latch node that won leadership. ZooKeeper transaction ids grow monotonically across the
     * whole ensemble, and every later leader's node was created after the previous leader's, so the czxid
     * increases strictly per term. The node's sequence number would not: LeaderLatch creates its parent as a
     * container node, which ZooKeeper deletes once empty, restarting the sequence.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun leadershipFencingToken(): Long {
        val path = leaderLatch?.lastPathIsLeader ?: return MutexOwner.NO_FENCING_TOKEN
        return try {
            curatorFramework.checkExists().forPath(path)?.czxid ?: MutexOwner.NO_FENCING_TOKEN
        } catch (error: Exception) {
            log.warn(error) { "leadershipFencingToken - mutex:[$mutex] contenderId:[$contenderId] - unavailable." }
            MutexOwner.NO_FENCING_TOKEN
        }
    }

    override fun notLeader() {
        notifyOwner(MutexOwner.NONE)
    }

    companion object {
        private val log = KotlinLogging.logger {}
        const val RESOURCE_PREFIX = "/simba/"
    }
}
