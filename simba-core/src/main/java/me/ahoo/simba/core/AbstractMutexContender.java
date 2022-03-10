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

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;

/**
 * @author ahoo wang
 */
@Slf4j
public abstract class AbstractMutexContender implements MutexContender {
    private final String mutex;
    private final String contenderId;
    
    public AbstractMutexContender(String mutex) {
        this(mutex, ContenderIdGenerator.Simple.HOST.generate());
    }
    
    public AbstractMutexContender(String mutex, String contenderId) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(mutex), "mutex can not be null or empty!");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(contenderId), "contenderId can not be null or empty!");
        this.mutex = mutex;
        this.contenderId = contenderId;
    }
    
    @Override
    public String getMutex() {
        return mutex;
    }
    
    @Override
    public String getContenderId() {
        return contenderId;
    }
    
    @Override
    public void onAcquired(MutexState mutexState) {
        if (log.isInfoEnabled()) {
            log.info("onAcquired - mutex:[{}] - contenderId:[{}].", mutex, contenderId);
        }
    }
    
    @Override
    public void onReleased(MutexState mutexState) {
        if (log.isInfoEnabled()) {
            log.info("onReleased - mutex:[{}] - contenderId:[{}].", mutex, contenderId);
        }
    }
    
}
