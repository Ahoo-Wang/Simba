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

package me.ahoo.simba.jdbc;

import me.ahoo.simba.core.MutexOwner;

/**
 * 互斥体实体
 *
 * @author ahoo wang
 */
public class MutexOwnerEntity extends MutexOwner {

    private final String mutex;

    /**
     * 版本号，用于领导者并发争抢控制
     */
    private int version;

    /**
     * 当前Db时间戳 (统一使用Db时间作为统一时间，防止全局时间不一致)
     * {@link java.util.concurrent.TimeUnit#MILLISECONDS}
     */
    private long currentDbAt;

    public MutexOwnerEntity(String mutex, String ownerId, long acquiredAt, long ttlAt, long transitionAt) {
        super(ownerId, acquiredAt, ttlAt, transitionAt);
        this.mutex = mutex;
    }

    public String getMutex() {
        return this.mutex;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public long getCurrentDbAt() {
        return currentDbAt;
    }

    public void setCurrentDbAt(long currentDbAt) {
        this.currentDbAt = currentDbAt;
    }
}
