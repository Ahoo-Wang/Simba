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
import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.recipes.leader.LeaderLatch.CloseMode
import org.apache.curator.framework.recipes.leader.LeaderLatchListener
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Zookeeper Mutex Contend Service.
 *
 * @author ahoo wang
 */
class ZookeeperMutexContendService(
    contender: MutexContender,
    handleExecutor: Executor,
    curatorFramework: CuratorFramework
) : AbstractMutexContendService(contender, handleExecutor), LeaderLatchListener {
    private val curatorFramework: CuratorFramework

    @Volatile
    private var leaderLatch: LeaderLatch? = null
    private val mutexPath: String

    init {
        mutexPath = RESOURCE_PREFIX + contender.mutex
        this.curatorFramework = curatorFramework
    }

    override fun startContend() {
        try {
            leaderLatch = LeaderLatch(curatorFramework, mutexPath, contenderId)
            leaderLatch!!.addListener(this)
            leaderLatch!!.start()
        } catch (e: Exception) {
            throw SimbaException(e)
        }
    }

    override fun stopContend() {
        try {
            leaderLatch!!.close(CloseMode.NOTIFY_LEADER)
            leaderLatch = null
        } catch (e: IOException) {
            throw SimbaException(e)
        }
    }

    override fun isLeader() {
        notifyOwner(MutexOwner(contenderId))
    }

    override fun notLeader() {
        notifyOwner(MutexOwner.NONE)
    }

    companion object {
        const val RESOURCE_PREFIX = "/simba/"
    }
}
