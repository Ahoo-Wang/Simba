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

import me.ahoo.simba.Simba;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Jdbc Properties.
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = JdbcProperties.PREFIX)
public class JdbcProperties {
    public static final String PREFIX = Simba.SIMBA_PREFIX + "jdbc";
    private boolean enabled = true;
    private Duration initialDelay = Duration.ofSeconds(0);
    private Duration ttl = Duration.ofSeconds(10);
    private Duration transition = Duration.ofSeconds(6);
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public Duration getInitialDelay() {
        return initialDelay;
    }
    
    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }
    
    public Duration getTtl() {
        return ttl;
    }
    
    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
    
    public Duration getTransition() {
        return transition;
    }
    
    public void setTransition(Duration transition) {
        this.transition = transition;
    }
}
