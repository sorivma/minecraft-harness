pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://nexus.gtnewhorizons.com/repository/public/")
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }

rootProject.name = "minecraft-harness"

include("agent-api", "agent-codex", "core", "chat-ui", "forge-1.7.10")
