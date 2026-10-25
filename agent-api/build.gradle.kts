plugins {
    `java-library`
    `java-test-fixtures`
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

val versionSource = layout.buildDirectory.dir("generated/sources/version/java")
val generateBuildVersion by
    tasks.registering {
        inputs.property("version", project.version)
        outputs.dir(versionSource)
        doLast {
            val output =
                versionSource.get().file("dev/minecraftharness/agent/BuildVersion.java").asFile
            output.parentFile.mkdirs()
            output.writeText(
                "package dev.minecraftharness.agent;\npublic final class BuildVersion {\n    public static final String VERSION = \"${project.version}\";\n    private BuildVersion() {}\n}\n"
            )
        }
    }

sourceSets.main { java.srcDir(generateBuildVersion) }
