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
    archiveBaseName.set("minecraft-harness-forge-1.7.10")
}

tasks.withType<JavaExec>().configureEach {
    if (name == "runClient")
        systemProperty("fml.coreMods.load", "dev.minecraftharness.forge.sound.SoundLoadingPlugin")
}

tasks.test {
    classpath += sourceSets.main.get().compileClasspath
    if (project.hasProperty("renderTests")) {
        dependsOn("extractNatives2")
        systemProperty("harness.renderTests", "true")
        systemProperty("org.lwjgl.librarypath", file("run/natives/lwjgl2").absolutePath)
    }
}
