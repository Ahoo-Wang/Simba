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
        try {
            latch.start()
        } catch (error: Throwable) {
            // A latch that failed to start must not keep its listener or a half-created node.
            try {
                latch.close(CloseMode.SILENT)
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
        leaderLatch = latch
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
        notifyOwner(MutexOwner(contenderId))
    }

    override fun notLeader() {
        notifyOwner(MutexOwner.NONE)
    }

    companion object {
        const val RESOURCE_PREFIX = "/simba/"
    }
}
