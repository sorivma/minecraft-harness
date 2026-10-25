pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://nexus.gtnewhorizons.com/repository/public/")
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "minecraft-harness"

include("agent-api", "agent-codex", "core", "chat-ui", "forge-1.7.10")
