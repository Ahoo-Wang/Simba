rootProject.name = "Simba"

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.codearte.gradle.nexus:gradle-nexus-staging-plugin:0.30.0")
    }
}
include(":simba-bom")
include(":simba-dependencies")
include(":simba-core")
include(":simba-jdbc")
include(":simba-redis")
include(":simba-zookeeper")
include(":simba-spring-boot-starter")
include(":simba-example")
