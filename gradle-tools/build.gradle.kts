// gradle-tools — Gradle build tools for Kalynx Copilot.
// Provides GradleBuildTool which lets the AI agent compile, test, build and publish
// Gradle projects by running gradlew/gradle in the project root.

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
    implementation(project(":tools-api"))

    intellijPlatform {
        local("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.3")
    }
}
