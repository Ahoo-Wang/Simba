description = "The Technology Compatibility Kit"

dependencies {
    api(project(":simba-core"))
    api(platform(libs.fluent.assert.bom))
    api("me.ahoo.test:fluent-assert-core")
    api("org.hamcrest:hamcrest")
    implementation("org.junit.jupiter:junit-jupiter-api")
}
