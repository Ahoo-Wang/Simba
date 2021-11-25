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

package me.ahoo.simba.core;

/**
 * @author ahoo wang
 */
public interface MutexRetrievalService extends AutoCloseable {

    MutexRetriever getRetriever();

    default String getMutex() {
        return getRetriever().getMutex();
    }

    MutexState getMutexState();

    default MutexOwner getBeforeOwner() {
        return getMutexState().getBefore();
    }

    /**
     * 当前持有者
     * 与分布式锁资源保持弱一致性
     *
     * @return
     */
    default MutexOwner getAfterOwner() {
        return getMutexState().getAfter();
    }

    /**
     * 当前是否存在持有者
     *
     * @return
     */
    default boolean hasOwner() {
        return getAfterOwner() != MutexOwner.NONE;
    }

    boolean isRunning();

    void start();

    void stop();
}
