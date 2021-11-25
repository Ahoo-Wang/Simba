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

package me.ahoo.simba.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import me.ahoo.simba.locker.Locker;
import me.ahoo.simba.locker.SimbaLocker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * @author ahoo wang
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JdbcLockerTest {
    private JdbcMutexOwnerRepository jdbcMutexOwnerRepository;
    private JdbcMutexContendServiceFactory contendServiceFactory;

    @BeforeAll
    void setup() {
        HikariDataSource hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl("jdbc:mysql://localhost:3306/simba_db");
        hikariDataSource.setUsername("root");
        hikariDataSource.setPassword("root");
        jdbcMutexOwnerRepository = new JdbcMutexOwnerRepository(hikariDataSource);
        contendServiceFactory = new JdbcMutexContendServiceFactory(jdbcMutexOwnerRepository,Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @Test
    void test() {
        String mutex = "locker-test";
        jdbcMutexOwnerRepository.tryInitMutex(mutex);
        try (Locker locker = new SimbaLocker(mutex, contendServiceFactory)) {
            locker.acquire();
            log.info("acquired");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testTimeout() {
        String mutex = "locker-test";
        jdbcMutexOwnerRepository.tryInitMutex(mutex);
        try (Locker locker = new SimbaLocker(mutex, contendServiceFactory)) {
            Assertions.assertThrows(TimeoutException.class, () -> {
                locker.acquire(Duration.ofSeconds(0));
            });
            log.info("acquired");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
