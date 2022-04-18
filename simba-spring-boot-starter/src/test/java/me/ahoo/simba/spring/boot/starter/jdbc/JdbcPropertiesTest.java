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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * JdbcPropertiesTest .
 *
 * @author ahoo wang
 */
class JdbcPropertiesTest {
    
    @Test
    void isEnabled() {
        JdbcProperties properties = new JdbcProperties();
        Assertions.assertTrue(properties.isEnabled());
    }
    
    @Test
    void setEnabled() {
        JdbcProperties properties = new JdbcProperties();
        properties.setEnabled(false);
        Assertions.assertFalse(properties.isEnabled());
    }
    
    @Test
    void getInitialDelay() {
        JdbcProperties properties = new JdbcProperties();
        Assertions.assertEquals(Duration.ofSeconds(0), properties.getInitialDelay());
    }
    
    @Test
    void setInitialDelay() {
        Duration initialDelay = Duration.ofSeconds(1);
        JdbcProperties properties = new JdbcProperties();
        properties.setInitialDelay(initialDelay);
        Assertions.assertEquals(initialDelay, properties.getInitialDelay());
    }
    
    @Test
    void getTtl() {
        Duration ttl = Duration.ofSeconds(10);
        JdbcProperties properties = new JdbcProperties();
        Assertions.assertEquals(ttl, properties.getTtl());
    }
    
    @Test
    void setTtl() {
        Duration ttl = Duration.ofSeconds(10);
        JdbcProperties properties = new JdbcProperties();
        properties.setTtl(ttl);
        Assertions.assertEquals(ttl, properties.getTtl());
    }
    
    @Test
    void getTransition() {
        JdbcProperties properties = new JdbcProperties();
        Assertions.assertEquals(Duration.ofSeconds(6), properties.getTransition());
    }
    
    @Test
    void setTransition() {
        Duration transition =  Duration.ofSeconds(8);
        JdbcProperties properties = new JdbcProperties();
        properties.setTransition(transition);
        Assertions.assertEquals(transition, properties.getTransition());
    }
}
