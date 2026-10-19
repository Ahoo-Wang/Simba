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

package me.ahoo.simba.spring.boot.starter.zookeeper

import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.zookeeper.ZookeeperLeaseBackend
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** A [ZookeeperLeaseBackend] on `simba.zookeeper.connect-string`. */
@AutoConfiguration
@ConditionalOnSimbaZookeeperEnabled
@ConditionalOnClass(ZookeeperLeaseBackend::class)
@EnableConfigurationProperties(ZookeeperProperties::class)
class SimbaZookeeperAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LeaseBackend::class)
    fun zookeeperLeaseBackend(properties: ZookeeperProperties): ZookeeperLeaseBackend =
        ZookeeperLeaseBackend(properties.connectString, properties.root, properties.connectionTimeout)
}
