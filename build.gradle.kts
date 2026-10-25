plugins {
    base
    id("com.diffplug.spotless") version "8.10.4"
}

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
    version = rootProject.providers.gradleProperty("version").get()
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
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            from(rootProject.file("LICENSE")) { into("META-INF") }
            from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
        }
        tasks.named("check") { dependsOn(rootProject.tasks.named("verifyArchitecture")) }
    }
}

tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }

tasks.named("assemble") { dependsOn(subprojects.map { "${it.path}:assemble" }) }

val releaseBundle by
    tasks.registering(Sync::class) {
        group = "distribution"
        description = "Collect the runtime mod, developer API, portable libraries and source JARs."
        dependsOn(tasks.named("assemble"))
        into(layout.buildDirectory.dir("release"))
        from("LICENSE", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md", "README.md")
        from("docs/releases/${project.version}.md") { rename { "RELEASE_NOTES.md" } }
        subprojects.forEach { module ->
            val artifact =
                if (module.name == "forge-1.7.10") "minecraft-harness-forge-1.7.10" else module.name
            from(module.layout.buildDirectory.dir("libs")) {
                include(
                    "$artifact-${project.version}.jar",
                    "$artifact-${project.version}-dev.jar",
                    "$artifact-${project.version}-sources.jar"
                )
                exclude("*-test-fixtures*.jar")
            }
        }
    }
