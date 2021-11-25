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

package me.ahoo.simba.locker;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * <p>
 * Utility for safely acquiring a lock and releasing it using Java 7's
 * try-with-resource feature.
 * </p>
 *
 * <p>
 * Canonical usage:
 * <code>
 *     try ( Locker locker = new SimbaLocker(mutex, contendServiceFactory) )
 *     {
 *          locker.acquire(); //locker.acquire(timeout);
 *         // do work
 *     }
 * </code>
 * </p>
 *
 * @author ahoo wang
 */
public interface Locker extends AutoCloseable {
    void acquire();
    void acquire(Duration timeout) throws TimeoutException;
}
