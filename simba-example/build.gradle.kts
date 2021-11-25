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

plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}
application {
    mainClass.set("me.ahoo.simba.example.ExampleApp")
}

dependencies {
//    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.slf4j:slf4j-api")
    implementation(platform(project(":simba-dependencies")))
    implementation("mysql:mysql-connector-java")
    implementation(project(":simba-jdbc"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    implementation(project(":simba-redis"))
    val depLombok = "org.projectlombok:lombok:${rootProject.ext.get("lombokVersion")}"
    compileOnly(depLombok)
    annotationProcessor(depLombok)
    implementation(project(":simba-zookeeper"))
    implementation(project(":simba-spring-boot-starter"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
