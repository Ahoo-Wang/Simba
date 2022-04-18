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

package me.ahoo.simba.core;

import com.google.errorprone.annotations.Immutable;

/**
 * Mutex Owner.
 *
 * @author ahoo wang
 */
@Immutable
public class MutexOwner {
    public static final String NONE_OWNER_ID = "";
    public static final MutexOwner NONE = new MutexOwner(NONE_OWNER_ID, 0, 0, 0);
    
    /**
     * 持有者Id.
     */
    private final String ownerId;
    
    /**
     * 获取到互斥锁的时间戳.
     */
    private final long acquiredAt;
    /**
     * 互斥锁的生存期（Time To Live）.
     * {@link java.util.concurrent.TimeUnit#MILLISECONDS}
     */
    private final long ttlAt;
    
    /**
     * 缓冲期/过渡期（绝对时间）
     * 1. 为了使领导权稳定，当前领导者可以在过渡期内优先续期
     * 2. 用于缓冲领导者任务执行时间
     * {@link java.util.concurrent.TimeUnit#MILLISECONDS}
     */
    private final long transitionAt;
    
    public MutexOwner(String ownerId) {
        this(ownerId, System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE);
    }
    
    public MutexOwner(String ownerId, long acquiredAt, long ttlAt, long transitionAt) {
        this.ownerId = ownerId;
        this.acquiredAt = acquiredAt;
        this.ttlAt = ttlAt;
        this.transitionAt = transitionAt;
    }
    
    public String getOwnerId() {
        return ownerId;
    }
    
    public long getAcquiredAt() {
        return acquiredAt;
    }
    
    public long getTtlAt() {
        return ttlAt;
    }
    
    public boolean isOwner(String contenderId) {
        return ownerId.equals(contenderId);
    }
    
    public long getCurrentAt() {
        return System.currentTimeMillis();
    }
    
    public long getTransitionAt() {
        return transitionAt;
    }
    
    public boolean isInTtl() {
        return ttlAt > getCurrentAt();
    }
    
    public boolean isInTtl(String contenderId) {
        return isOwner(contenderId) && isInTtl();
    }
    
    /**
     * 判断是否在过渡期内.
     *
     * @return boolean
     */
    public boolean isInTransition() {
        return this.transitionAt >= getCurrentAt();
    }
    
    public boolean isInTransitionOf(String contenderId) {
        return isOwner(contenderId)
            && isInTransition();
    }
    
    /**
     * 判断 是否当前存在领导者 ({@link #transitionAt} &gt;= {@link #getCurrentAt()}).
     *
     * @return boolean
     */
    public boolean hasOwner() {
        return transitionAt >= getCurrentAt();
    }
}
