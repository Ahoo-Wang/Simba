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

package me.ahoo.simba.spring.boot.starter.redis;

import static org.junit.jupiter.api.Assertions.*;

import me.ahoo.simba.spring.boot.starter.jdbc.JdbcProperties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * RedisPropertiesTest .
 *
 * @author ahoo wang
 */
class RedisPropertiesTest {
    
    @Test
    void isEnabled() {
        RedisProperties properties = new RedisProperties();
        Assertions.assertTrue(properties.isEnabled());
    }
    
    @Test
    void setEnabled() {
        RedisProperties properties = new RedisProperties();
        properties.setEnabled(false);
        Assertions.assertFalse(properties.isEnabled());
    }
    
    @Test
    void getTtl() {
        Duration ttl = Duration.ofSeconds(10);
        RedisProperties properties = new RedisProperties();
        Assertions.assertEquals(ttl, properties.getTtl());
    }
    
    @Test
    void setTtl() {
        Duration ttl = Duration.ofSeconds(10);
        RedisProperties properties = new RedisProperties();
        properties.setTtl(ttl);
        Assertions.assertEquals(ttl, properties.getTtl());
    }
    
    @Test
    void getTransition() {
        RedisProperties properties = new RedisProperties();
        Assertions.assertEquals(Duration.ofSeconds(6), properties.getTransition());
    }
    
    @Test
    void setTransition() {
        Duration transition = Duration.ofSeconds(8);
        RedisProperties properties = new RedisProperties();
        properties.setTransition(transition);
        Assertions.assertEquals(transition, properties.getTransition());
    }
}
