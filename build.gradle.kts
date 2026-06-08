// Root build — shared conventions only.
// All IntelliJ Platform configuration lives in each subproject's build.gradle.kts.

subprojects {
    apply(plugin = "java")

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
