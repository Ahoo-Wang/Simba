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

package me.ahoo.simba.example;

import me.ahoo.simba.core.AbstractMutexContender;
import me.ahoo.simba.core.MutexState;
import me.ahoo.simba.jdbc.JdbcMutexContendService;
import org.slf4j.Logger;

/**
 * @author ahoo wang
 */

public class ExampleContender extends AbstractMutexContender {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(JdbcMutexContendService.class);
    public ExampleContender() {
        super("example-mutex");
    }

    @Override
    public void onAcquired(MutexState mutexState) {
        super.onAcquired(mutexState);
        log.warn("------ onAcquired ------");
    }

    @Override
    public void onReleased(MutexState mutexState) {
        super.onReleased(mutexState);
        log.warn("------ onReleased ------");
    }
}
