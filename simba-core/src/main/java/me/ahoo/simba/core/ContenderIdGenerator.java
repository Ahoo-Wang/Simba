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

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.SimbaException;
import me.ahoo.simba.util.Systems;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ahoo wang
 */
public interface ContenderIdGenerator {

    String generate();

    @Slf4j
    class Uuid implements ContenderIdGenerator {
        public static final ContenderIdGenerator INSTANCE = new Uuid();

        @Override
        public String generate() {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    @Slf4j
    class Host implements ContenderIdGenerator {

        public static final ContenderIdGenerator INSTANCE = new Host();

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
    }
}
