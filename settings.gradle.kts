rootProject.name = "kalynx-copilot"

include("tools-api", "copilot", "maven-tools", "gradle-tools")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
