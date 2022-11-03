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

package me.ahoo.simba.zookeeper;

import static org.apache.curator.framework.recipes.leader.LeaderLatch.CloseMode.NOTIFY_LEADER;

import me.ahoo.simba.SimbaException;
import me.ahoo.simba.core.AbstractMutexContendService;
import me.ahoo.simba.core.MutexContender;
import me.ahoo.simba.core.MutexOwner;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;

import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Zookeeper Mutex Contend Service.
 *
 * @author ahoo wang
 */
public class ZookeeperMutexContendService extends AbstractMutexContendService implements LeaderLatchListener {
    
    public static final String RESOURCE_PREFIX = "/simba/";
    
    private final CuratorFramework curatorFramework;
    private volatile LeaderLatch leaderLatch;
    private final String mutexPath;
    
    public ZookeeperMutexContendService(MutexContender contender, Executor handleExecutor, CuratorFramework curatorFramework) {
        super(contender, handleExecutor);
        this.mutexPath = RESOURCE_PREFIX + contender.getMutex();
        this.curatorFramework = curatorFramework;
    }
    
    @Override
    protected void startContend() {
        try {
            this.leaderLatch = new LeaderLatch(curatorFramework, mutexPath, getContenderId());
            this.leaderLatch.addListener(this);
            this.leaderLatch.start();
        } catch (Exception e) {
            throw new SimbaException(e);
        }
    }
    
    @Override
    protected void stopContend() {
        try {
            this.leaderLatch.close(NOTIFY_LEADER);
            this.leaderLatch = null;
        } catch (IOException e) {
            throw new SimbaException(e);
        }
    }
    
    @Override
    public void isLeader() {
        notifyOwner(new MutexOwner(getContenderId()));
    }
    
    @Override
    public void notLeader() {
        notifyOwner(MutexOwner.NONE);
    }
}
