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

import java.util.concurrent.ThreadLocalRandom;

/**
 * Contend Period.
 *
 * @author ahoo wang
 */
public class ContendPeriod {
    
    private final String contenderId;
    
    public ContendPeriod(String contenderId) {
        this.contenderId = contenderId;
    }
    
    public long ensureNextDelay(MutexOwner mutexOwner) {
        long nextDelay = nextDelay(mutexOwner);
        return nextDelay < 0 ? 0 : nextDelay;
    }
    
    public long nextDelay(MutexOwner mutexOwner) {
        if (mutexOwner.isOwner(contenderId)) {
            return nextOwnerDelay(mutexOwner);
        }
        return nextContenderDelay(mutexOwner);
    }
    
    public static long nextOwnerDelay(MutexOwner mutexOwner) {
        return mutexOwner.getTtlAt() - System.currentTimeMillis();
    }
    
    public static long nextContenderDelay(MutexOwner mutexOwner) {
        final long transition = mutexOwner.getTransitionAt() - mutexOwner.getTtlAt();
        final long max = 1000;
        long min = -200;
        if (transition == 0) {
            min = 0;
        }
        final long random = ThreadLocalRandom.current().nextLong(min, max);
        final long now = System.currentTimeMillis();
        return mutexOwner.getTransitionAt() - now + random;
    }
    
    
}
