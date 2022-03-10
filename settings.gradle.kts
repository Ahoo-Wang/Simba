rootProject.name = "Simba"

include(":simba-bom")
include(":simba-dependencies")
include(":simba-core")
include(":simba-jdbc")
include(":simba-redis")
include(":simba-spring-redis")
include(":simba-zookeeper")
include(":simba-spring-boot-starter")
include(":simba-example")

buildscript{
    repositories{
        gradlePluginPortal()
    }
    dependencies{
        classpath("me.champeau.jmh:jmh-gradle-plugin:0.6.6")
        classpath("io.github.gradle-nexus:publish-plugin:1.1.0")
        classpath("com.github.spotbugs.snom:spotbugs-gradle-plugin:5.0.4")
    }
}
