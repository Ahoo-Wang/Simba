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

description = "Spring Boot 4 starter for Simba: the Simba bean, backend auto-configuration, @SimbaScheduled, @SimbaPartitioned, metrics, Actuator endpoint and health"

plugins {
    alias(libs.plugins.kotlin.spring)
    kotlin("kapt")
}
kapt {
    arguments {
        arg(
            "org.springframework.boot.configurationprocessor.additionalMetadataLocations",
            file("src/main/resources").absolutePath
        )
    }
}
tasks.matching { it.name == "kaptKotlin" }.configureEach {
    inputs.file("src/main/resources/META-INF/additional-spring-configuration-metadata.json")
}
java {
    registerFeature("springRedisSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "spring-redis-support", version.toString())
    }
    registerFeature("jdbcSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "jdbc-support", version.toString())
    }
    registerFeature("zookeeperSupport") {
        usingSourceSet(sourceSets[SourceSet.MAIN_SOURCE_SET_NAME])
        capability(group.toString(), "zookeeper-support", version.toString())
    }
}

dependencies {
    kapt(platform(project(":simba-dependencies")))
    api(project(":simba-core"))

    "springRedisSupportImplementation"(project(":simba-spring-redis"))
    "springRedisSupportImplementation"("org.springframework.boot:spring-boot-starter-data-redis")

    "jdbcSupportImplementation"(project(":simba-jdbc"))

    "zookeeperSupportImplementation"(project(":simba-zookeeper"))

    api("org.springframework.boot:spring-boot-starter")
    // Binds the Kotlin @ConfigurationProperties data classes through their primary constructor (with defaults).
    implementation(kotlin("reflect"))
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-health")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.micrometer:micrometer-core")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-health")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.springframework.boot:spring-boot-micrometer-metrics")
    testImplementation("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation(libs.curator.test)
    testImplementation("io.lettuce:lettuce-core")
    testImplementation(project(":simba-testing"))
}
