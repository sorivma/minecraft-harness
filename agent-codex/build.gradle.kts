plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

dependencies {
    api(project(":agent-api"))
    implementation("com.google.code.gson:gson:2.2.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":agent-api")))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

tasks.test {
    // Java 8's Windows launcher narrows argv through the system ANSI code page.
    // Keep the tested parent on Java 8; use a Unicode-capable native echo child.
    val nativeJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    systemProperty("harness.test.nativeJava", nativeJava.get().executablePath.asFile.absolutePath)
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
