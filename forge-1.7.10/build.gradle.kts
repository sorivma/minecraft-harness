plugins {
    java
    id("com.gtnewhorizons.retrofuturagradle") version "1.4.9"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

minecraft { mcVersion.set("1.7.10") }

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core"))
    implementation(project(":chat-ui"))
    implementation(project(":agent-codex"))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

tasks.jar {
    manifest.attributes(
        "FMLCorePlugin" to "dev.minecraftharness.forge.sound.SoundLoadingPlugin",
        "FMLCorePluginContainsFMLMod" to "true"
    )
    from(project(":core").sourceSets.main.get().output)
    from(project(":agent-api").sourceSets.main.get().output)
    from(project(":agent-codex").sourceSets.main.get().output)
    from(project(":chat-ui").sourceSets.main.get().output)
    // Ship the portable Markdown parser, its extensions, resources and license in the mod.
    from({
        configurations.runtimeClasspath
            .get()
            .filter { it.name.startsWith("commonmark-") || it.name.startsWith("autolink-") }
            .map { zipTree(it) }
    }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/maven/**",
            "META-INF/versions/**",
            "module-info.class"
        )
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    archiveBaseName.set("minecraft-harness-forge-1.7.10")
}

// Reobfuscation resolves the same runtime artifacts used by the bundled parser.
tasks.named("reobfJar") {
    dependsOn(":agent-api:jar", ":core:jar", ":agent-codex:jar", ":chat-ui:jar")
}

val installLocalMod by
    tasks.registering(Copy::class) {
        group = "development"
        description = "Installs the current reobfuscated mod for the GTNH relaunched client."
        dependsOn("reobfJar")
        from(
            layout.buildDirectory.file("libs/minecraft-harness-forge-1.7.10-${project.version}.jar")
        )
        into(layout.projectDirectory.dir("run/mods"))
    }

tasks.withType<JavaExec>().configureEach {
    if (name == "runClient") {
        dependsOn(installLocalMod)
        systemProperty("fml.coreMods.load", "dev.minecraftharness.forge.sound.SoundLoadingPlugin")
        systemProperty("harness.testWorldDirectory", file("run").absolutePath)
    }
}

tasks.test {
    classpath += sourceSets.main.get().compileClasspath
    if (project.hasProperty("renderTests")) {
        dependsOn("extractNatives2")
        systemProperty("harness.renderTests", "true")
        systemProperty("org.lwjgl.librarypath", file("run/natives/lwjgl2").absolutePath)
    }
}
