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

import me.ahoo.simba.SimbaException;
import me.ahoo.simba.util.Systems;

import com.google.common.base.Strings;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contender Id Generator.
 *
 * @author ahoo wang
 */
public interface ContenderIdGenerator {
    
    String generate();
    
    enum Simple implements ContenderIdGenerator {
        UUID {
            @Override
            public String generate() {
                return java.util.UUID.randomUUID().toString().replace("-", "");
            }
        },
        HOST {
            private final AtomicLong counter = new AtomicLong();
            
            @Override
            public String generate() {
                try {
                    InetAddress localHost = InetAddress.getLocalHost();
                    long processId = Systems.getCurrentProcessId();
                    long seq = counter.getAndIncrement();
                    return Strings.lenientFormat("%s:%s@%s", seq, processId, localHost.getHostAddress());
                } catch (UnknownHostException unknownHostException) {
                    throw new SimbaException(unknownHostException.getMessage(), unknownHostException);
                }
            }
        };
        
        @Override
        public abstract String generate();
    }
}
