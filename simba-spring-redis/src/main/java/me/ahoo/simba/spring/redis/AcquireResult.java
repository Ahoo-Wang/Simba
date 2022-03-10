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

package me.ahoo.simba.spring.redis;

import com.google.common.base.Strings;
import me.ahoo.simba.core.MutexOwner;

/**
 * @author ahoo wang
 */
public class AcquireResult {

    public static final AcquireResult NONE = new AcquireResult(MutexOwner.NONE_OWNER_ID, 0L);

    private final String ownerId;
    private final long transitionAt;

    public AcquireResult(String ownerId, long transitionAt) {
        this.ownerId = ownerId;
        this.transitionAt = transitionAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public long getTransitionAt() {
        return transitionAt;
    }

    /**
     * @param resultStr {ownerId}:{transitionAt}
     * @return
     */
    public static AcquireResult of(String resultStr) {
        if (OwnerEvent.DELIMITER.equals(resultStr)) {
            return NONE;
        }
        String[] msgs = resultStr.split(OwnerEvent.DELIMITER);
        if (msgs.length != 2) {
            throw new IllegalStateException(Strings.lenientFormat("Incorrect resultStr format:[%s]", resultStr));
        }
        String ownerId = msgs[0];
        long keyTtl = Long.parseLong(msgs[1]);
        long transitionAt = System.currentTimeMillis() + keyTtl;
        return new AcquireResult(ownerId, transitionAt);
    }
}
