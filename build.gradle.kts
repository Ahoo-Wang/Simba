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

import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    jacoco
}
val dependenciesProject = project(":simba-dependencies")
val bomProjects = setOf(
    project(":simba-bom"),
    dependenciesProject,
)
val exampleProjects = setOf(
    project(":simba-example")
)

/** Kotlin API, language and stdlib version Simba's libraries are compiled for. */
val KOTLIN_BASELINE = "2.2.21"

val codeCoverageReportProject = project(":code-coverage-report")
val testJavaVersion: Provider<Int> = providers.gradleProperty("testJavaVersion").map { it.toInt() }.orElse(21)
// simba-dependencies is a build-time platform only (see versionPlatform below): never published.
val publishProjects = subprojects - exampleProjects - codeCoverageReportProject - dependenciesProject
val libraryProjects = publishProjects - bomProjects

ext.set("libraryProjects", libraryProjects)

allprojects {
    repositories {
        mavenCentral()
        // -PuseMavenLocal resolves locally installed snapshots, after Maven Central.
        if (providers.gradleProperty("useMavenLocal").isPresent) {
            mavenLocal()
        }
    }
    apply<DetektPlugin>()
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
    }
    dependencies {
        detektPlugins(rootProject.libs.detekt.formatting)
    }
}

configure(bomProjects) {
    apply<JavaPlatformPlugin>()
    configure<JavaPlatformExtension> {
        allowDependencies()
    }
}

configure(libraryProjects) {
    apply<DokkaPlugin>()
    apply<JacocoPlugin>()
    apply<JavaLibraryPlugin>()
    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        // Consumers compile against Kotlin 2.2 metadata and stdlib (the Spring Boot 4 baseline), whatever compiler
        // builds Simba: the versionPlatform below pins kotlin-stdlib / kotlin-reflect strictly to KOTLIN_BASELINE, so
        // the published POM and module metadata ask for 2.2 even though the Spring Boot BOM manages a newer one.
        coreLibrariesVersion = KOTLIN_BASELINE
        compilerOptions {
            apiVersion.set(KotlinVersion.KOTLIN_2_2)
            languageVersion.set(KotlinVersion.KOTLIN_2_2)
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict")
            // Interface methods with bodies become Java default methods, plus DefaultImpls for older Kotlin callers
            // (the former -Xjvm-default=all-compatibility).
            jvmDefault.set(JvmDefaultMode.ENABLE)
            javaParameters = true
        }
    }
    val dokkaHtml = tasks.named("dokkaGeneratePublicationHtml")
    tasks.named<Jar>("javadocJar") {
        dependsOn(dokkaHtml)
        from(dokkaHtml)
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        manifest {
            attributes(
                "Automatic-Module-Name" to "me.ahoo.simba." + project.name.removePrefix("simba-").replace('-', '.'),
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }
    }
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters"))
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        // Bytecode targets the JDK 21 baseline; -PtestJavaVersion=N runs the tests on a newer JVM (CI: JDK 25).
        javaLauncher.set(
            project.the<JavaToolchainService>().launcherFor {
                languageVersion.set(testJavaVersion.map { JavaLanguageVersion.of(it) })
            }
        )
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        // fix logging missing code for JacocoPlugin
        jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
    }
    // simba-dependencies pins versions at build time only: it is not published as a dependency, so consumers get no
    // Spring Boot constraints; the published POM and module metadata carry the resolved versions instead.
    val versionPlatform = configurations.create("versionPlatform") {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    configurations.configureEach {
        val classpath = name.endsWith("CompileClasspath", ignoreCase = true) ||
            name.endsWith("RuntimeClasspath", ignoreCase = true) ||
            name.endsWith("AnnotationProcessor", ignoreCase = true) ||
            name.startsWith("kapt")
        if (classpath && this != versionPlatform) {
            extendsFrom(versionPlatform)
        }
    }
    dependencies {
        versionPlatform(platform(dependenciesProject))
        constraints {
            listOf("kotlin-stdlib", "kotlin-reflect").forEach { artifact ->
                versionPlatform("org.jetbrains.kotlin:$artifact") {
                    version { strictly(KOTLIN_BASELINE) }
                    because("Simba is compiled for Kotlin $KOTLIN_BASELINE; consumers may use any newer stdlib")
                }
            }
        }
        testImplementation(platform(rootProject.libs.junit.bom))
        testImplementation(platform(rootProject.libs.fluent.assert.bom))
        testImplementation(platform(rootProject.libs.testcontainers.bom))
        implementation("org.slf4j:slf4j-api")
        testImplementation("ch.qos.logback:logback-classic")
        testImplementation("me.ahoo.test:fluent-assert-core")
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testImplementation(rootProject.libs.hamcrest)
        testImplementation(rootProject.libs.mockk) {
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }
}

configure(publishProjects) {
    val isBom = bomProjects.contains(this)
    apply<MavenPublishPlugin>()
    apply<SigningPlugin>()
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "projectBuildRepo"
                url = uri(layout.buildDirectory.dir("repos"))
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Ahoo-Wang/Simba")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
            maven {
                name = "LinYiPackages"
                url = uri(project.properties["linyiPackageReleaseUrl"].toString())
                credentials {
                    username = project.properties["linyiPackageUsername"]?.toString()
                    password = project.properties["linyiPackagePwd"]?.toString()
                }
            }
        }
        publications {
            val publishName = if (isBom) "mavenBom" else "mavenLibrary"
            val publishComponentName = if (isBom) "javaPlatform" else "java"
            create<MavenPublication>(publishName) {
                from(components[publishComponentName])
                if (!isBom) {
                    versionMapping {
                        usage("java-api") {
                            fromResolutionOf("runtimeClasspath")
                        }
                        usage("java-runtime") {
                            fromResolutionResult()
                        }
                    }
                }
                pom {
                    name.set(project.name)
                    // Lazily: the module scripts that set `description` run after this root configuration.
                    description.set(provider { project.description ?: getPropertyOf("description") })
                    url.set(getPropertyOf("website"))
                    issueManagement {
                        system.set("GitHub")
                        url.set(getPropertyOf("issues"))
                    }
                    scm {
                        url.set(getPropertyOf("website"))
                        connection.set("scm:git:${getPropertyOf("vcs")}")
                        developerConnection.set("scm:git:${getPropertyOf("vcs")}")
                    }
                    licenses {
                        license {
                            name.set(getPropertyOf("license_name"))
                            url.set(getPropertyOf("license_url"))
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("ahoo-wang")
                            name.set("ahoo wang")
                            organization {
                                url.set(getPropertyOf("website"))
                            }
                        }
                    }
                }
            }
        }
    }
    configure<SigningExtension> {
        val isInCI = null != System.getenv("CI")
        if (isInCI) {
            val signingKeyId = System.getenv("SIGNING_KEYID")
            val signingKey = System.getenv("SIGNING_SECRETKEY")
            val signingPassword = System.getenv("SIGNING_PASSWORD")
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }
        if (isBom) {
            sign(extensions.getByType(PublishingExtension::class).publications.get("mavenBom"))
        } else {
            sign(extensions.getByType(PublishingExtension::class).publications.get("mavenLibrary"))
        }
    }
}

nexusPublishing {
    this.repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

fun getPropertyOf(name: String) = project.properties[name]?.toString()
