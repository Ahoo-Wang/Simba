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
import me.ahoo.simba.core.ContenderIdGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * @author ahoo wang
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMutexOwnerRepositoryTest {
    private JdbcMutexOwnerRepository jdbcMutexOwnerRepository;

    @BeforeAll
    void setup() {
        HikariDataSource hikariDataSource = new HikariDataSource();
        hikariDataSource.setJdbcUrl("jdbc:mysql://localhost:3306/simba_db");
        hikariDataSource.setUsername("root");
        hikariDataSource.setPassword("root");
        jdbcMutexOwnerRepository = new JdbcMutexOwnerRepository(hikariDataSource);
    }

    @Test
    void getOwner() {
        jdbcMutexOwnerRepository.tryInitMutex("getOwner");
        MutexOwnerEntity entity = jdbcMutexOwnerRepository.getOwner("getOwner");
        Assertions.assertNotNull(entity);
    }


    @Test
    void acquire() {
        long ttl = 100;
        long transition = 100;
        String ownerId = ContenderIdGenerator.Host.INSTANCE.generate();
        String mutex = "acquire_" + ownerId;
        jdbcMutexOwnerRepository.tryInitMutex(mutex);
        boolean succeeded = jdbcMutexOwnerRepository.acquire(mutex, ownerId, ttl, transition);
        Assertions.assertTrue(succeeded);
        MutexOwnerEntity entity = jdbcMutexOwnerRepository.getOwner(mutex);
        Assertions.assertNotNull(entity);
        Assertions.assertEquals(ownerId, entity.getOwnerId());
        succeeded = jdbcMutexOwnerRepository.acquire(mutex, ownerId, ttl, transition);
        Assertions.assertTrue(succeeded);
    }

}
