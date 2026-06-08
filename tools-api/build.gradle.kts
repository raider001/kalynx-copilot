// tools-api — the plugin contract.
// Defines AgentTool (the extension interface) and ProcessRunner (shell execution utility).
// Any module that implements or uses tools depends on this.

plugins {
    id("org.jetbrains.intellij.platform.module") version "2.3.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    intellijPlatform {
        local("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.3")
    }
}
