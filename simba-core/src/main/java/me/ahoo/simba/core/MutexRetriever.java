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
 * Mutex Retriever.
 *
 * @author ahoo wang
 */
public interface MutexRetriever {
    /**
     * 互斥锁资源名称.
     *
     * @return mutex
     */
    String getMutex();
    
    /**
     * 当互斥锁持有者发生变化时，回调该方法.
     *
     * @param mutexState 持有者状态
     */
    void notifyOwner(MutexState mutexState);
}
