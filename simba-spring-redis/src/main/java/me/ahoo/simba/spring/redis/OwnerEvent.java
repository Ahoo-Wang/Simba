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

package me.ahoo.simba.spring.redis;

import com.google.common.base.Strings;

/**
 * Owner Event.
 *
 * @author ahoo wang
 */
public class OwnerEvent {
    
    public static final String EVENT_RELEASED = "released";
    public static final String EVENT_ACQUIRED = "acquired";
    
    public static final String DELIMITER = "@@";
    private final String event;
    private final String ownerId;
    private final long eventAt;
    
    public OwnerEvent(String event, String ownerId) {
        this.eventAt = System.currentTimeMillis();
        this.event = event;
        this.ownerId = ownerId;
    }
    
    public long getEventAt() {
        return eventAt;
    }
    
    public String getEvent() {
        return event;
    }
    
    public String getOwnerId() {
        return ownerId;
    }
    
    public static OwnerEvent of(String message) {
        String[] msgs = message.split(DELIMITER);
        if (msgs.length != 2) {
            throw new IllegalStateException(Strings.lenientFormat("Incorrect message format:[%s]", message));
        }
        return new OwnerEvent(msgs[0], msgs[1]);
    }
}
