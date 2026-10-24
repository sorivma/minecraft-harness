plugins {
    `java-library`
    `java-test-fixtures`
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

dependencies {
    api(project(":core"))
    api(project(":agent-api"))
    // 0.21 is the last CommonMark release compatible with the Java 8 game runtime.
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.21.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.21.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.21.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":agent-api")))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
