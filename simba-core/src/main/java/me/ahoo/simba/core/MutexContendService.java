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
 * Mutex Contend Service.
 *
 * @author ahoo wang
 */
public interface MutexContendService extends MutexRetrievalService {
    
    /**
     * 当前竞争服务绑定的竞争者.
     *
     * @return MutexContender
     */
    MutexContender getContender();
    
    default String getContenderId() {
        return getContender().getContenderId();
    }
    
    /**
     * 当前竞争者是否是持有者.
     *
     * @return boolean
     */
    default boolean isOwner() {
        return getAfterOwner().isOwner(getContenderId());
    }
    
    default boolean isInTtl() {
        return getAfterOwner().isInTtl(getContenderId());
    }
}
