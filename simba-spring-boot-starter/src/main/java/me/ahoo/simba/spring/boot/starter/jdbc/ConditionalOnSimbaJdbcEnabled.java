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

package me.ahoo.simba.spring.boot.starter.jdbc;


import me.ahoo.simba.jdbc.JdbcMutexContendServiceFactory;
import me.ahoo.simba.spring.boot.starter.ConditionalOnSimbaEnabled;
import me.ahoo.simba.spring.boot.starter.EnabledSuffix;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author ahoo wang
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnSimbaEnabled
@ConditionalOnProperty(value = ConditionalOnSimbaJdbcEnabled.ENABLED_KEY, matchIfMissing = false, havingValue = "true")
@ConditionalOnClass(JdbcMutexContendServiceFactory.class)
public @interface ConditionalOnSimbaJdbcEnabled {
    String ENABLED_KEY = JdbcProperties.PREFIX + EnabledSuffix.KEY;
}
