/*
 * Copyright [2021-2021] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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

import me.ahoo.simba.SimbaException;
import me.ahoo.simba.core.AbstractMutexContendService;
import me.ahoo.simba.core.MutexContender;
import me.ahoo.simba.core.MutexOwner;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import java.io.IOException;
import java.util.concurrent.Executor;

import static org.apache.curator.framework.recipes.leader.LeaderLatch.CloseMode.NOTIFY_LEADER;


/**
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

    /**
     * This is called when the LeaderLatch's state goes from hasLeadership = false to hasLeadership = true.
     * <p>
     * Note that it is possible that by the time this method call happens, hasLeadership has fallen back to false.  If
     * this occurs, you can expect {@link #notLeader()} to also be called.
     */
    @Override
    public void isLeader() {
        notifyOwner(new MutexOwner(getContenderId()));
    }

    /**
     * This is called when the LeaderLatch's state goes from hasLeadership = true to hasLeadership = false.
     * <p>
     * Note that it is possible that by the time this method call happens, hasLeadership has become true.  If
     * this occurs, you can expect {@link #isLeader()} to also be called.
     */
    @Override
    public void notLeader() {
        notifyOwner(MutexOwner.NONE);
    }
}
