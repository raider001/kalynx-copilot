// copilot — the main IntelliJ plugin module.
// Depends on all other submodules and produces the deployable plugin artifact.

plugins {
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.kalynx"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":tools-api"))
    implementation(project(":maven-tools"))
    implementation(project(":gradle-tools"))

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")

    intellijPlatform {
        local("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.3")
        bundledPlugin("com.intellij.java")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.kalynx.copilot"
        name = "Kalynx Copilot"
        version = project.version.toString()
        description = """
            Kalynx Copilot — an agentic AI coding assistant powered by any
            OpenAI-compatible API. Features a chat tool window with multi-turn
            conversations, tool-calling capabilities (read/write files, search
            project, run Maven/Gradle builds), and context-aware code assistance.
        """.trimIndent()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

// buildSearchableOptions launches the IDE headlessly to index settings UI — not needed and
// often fails with local installations. The plugin works correctly without it.
tasks.named("buildSearchableOptions") {
    enabled = false
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val coroutinesJar = file("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.3/plugins/Kotlin/kotlinc/lib/kotlinx-coroutines-core-jvm.jar")
    if (coroutinesJar.exists()) {
        coroutinesJavaAgentFile = coroutinesJar
    }
}
