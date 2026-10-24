plugins { id("com.diffplug.spotless") version "6.25.0" }

spotless {
    java {
        target("*/src/**/*.java")
        googleJavaFormat("1.19.2").aosp()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktfmt("0.50").kotlinlangStyle()
    }
}

allprojects {
    group = "dev.minecraftharness"
    version = "0.1.0"
    repositories { mavenCentral() }
}

val verifyArchitecture by
    tasks.registering {
        group = "verification"
        description = "Checks the portable module boundaries and production dependency graph."
        doLast {
            val allowed =
                mapOf(
                    "agent-api" to emptySet<String>(),
                    "core" to setOf("agent-api"),
                    "chat-ui" to setOf("core", "agent-api"),
                    "agent-codex" to setOf("agent-api"),
                    "forge-1.7.10" to setOf("core", "agent-api", "chat-ui", "agent-codex")
                )
            for ((name, targets) in allowed) {
                val module = project(":$name")
                for (configuration in
                    listOf("api", "implementation", "compileOnly", "runtimeOnly")) {
                    module.configurations.findByName(configuration)?.allDependencies?.forEach {
                        dependency ->
                        if (dependency is org.gradle.api.artifacts.ProjectDependency) {
                            check(dependency.dependencyProject.name in targets) {
                                "Forbidden dependency: $name -> ${dependency.dependencyProject.name}"
                            }
                        } else if (name in setOf("agent-api", "core", "chat-ui")) {
                            val markdown =
                                name == "chat-ui" &&
                                    dependency.group == "org.commonmark" &&
                                    dependency.version == "0.21.0" &&
                                    dependency.name in
                                        setOf(
                                            "commonmark",
                                            "commonmark-ext-gfm-tables",
                                            "commonmark-ext-gfm-strikethrough",
                                            "commonmark-ext-task-list-items",
                                            "commonmark-ext-autolink"
                                        )
                            check(markdown) {
                                "Portable module $name has external production dependency ${dependency.group}:${dependency.name}"
                            }
                        }
                    }
                }
                if (name != "forge-1.7.10") {
                    module
                        .fileTree("src/main/java")
                        .matching { include("**/*.java") }
                        .forEach { file ->
                            val source = file.readText()
                            val forbidden =
                                mutableListOf(
                                    "net.minecraft",
                                    "net.minecraftforge",
                                    "cpw.mods",
                                    "org.lwjgl"
                                )
                            if (name != "agent-codex")
                                forbidden.addAll(
                                    listOf(
                                        "com.google.gson",
                                        "com.fasterxml.jackson",
                                        "org.json",
                                        "dev.minecraftharness.codex"
                                    )
                                )
                            for (prefix in forbidden) check(!source.contains(prefix)) {
                                "$file references forbidden namespace $prefix"
                            }
                        }
                }
            }
        }
    }

subprojects {
    plugins.withId("java") {
        tasks.named("check") { dependsOn(rootProject.tasks.named("verifyArchitecture")) }
    }
}
