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

/**
 * Mutex State.
 *
 * @author ahoo wang
 */
public class MutexState {
    public static final MutexState NONE = new MutexState(MutexOwner.NONE, MutexOwner.NONE);
    /**
     * 旧的持有者.
     */
    private final MutexOwner before;
    /**
     * 新的持有者.
     */
    private final MutexOwner after;
    
    public MutexState(MutexOwner before, MutexOwner after) {
        this.before = before;
        this.after = after;
    }
    
    public MutexOwner getBefore() {
        return before;
    }
    
    public MutexOwner getAfter() {
        return after;
    }
    
    public boolean isChanged() {
        return !before.isOwner(after.getOwnerId());
    }
    
    public boolean isAcquired(String contenderId) {
        return isChanged() && isOwner(contenderId);
    }
    
    public boolean isReleased(String contenderId) {
        return isChanged() && before.isOwner(contenderId);
    }
    
    public boolean isOwner(String contenderId) {
        return after.isOwner(contenderId);
    }
    
    public boolean isInTtl(String contenderId) {
        return isOwner(contenderId) && after.isInTtl(contenderId);
    }
}
