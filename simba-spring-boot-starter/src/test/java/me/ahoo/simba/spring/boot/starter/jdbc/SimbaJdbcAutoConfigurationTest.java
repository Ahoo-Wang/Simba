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

package me.ahoo.simba.spring.boot.starter.jdbc;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import me.ahoo.simba.core.MutexContendServiceFactory;
import me.ahoo.simba.jdbc.MutexOwnerRepository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SimbaJdbcAutoConfigurationTest .
 *
 * @author ahoo wang
 */
class SimbaJdbcAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();
    
    @Test
    void contextLoads() {
        this.contextRunner
            .withPropertyValues("spring.datasource.url=jdbc:mysql://localhost:3306/simba_db")
            .withUserConfiguration(DataSourceAutoConfiguration.class, SimbaJdbcAutoConfiguration.class)
            .run(context -> {
                assertThat(context)
                    .hasSingleBean(SimbaJdbcAutoConfiguration.class)
                    .hasSingleBean(JdbcProperties.class)
                    .hasSingleBean(MutexOwnerRepository.class)
                    .hasSingleBean(MutexContendServiceFactory.class)
                ;
            });
    }
}
