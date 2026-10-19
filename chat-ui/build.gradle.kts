plugins {
    `java-library`
    `java-test-fixtures`
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

dependencies {
    api(project(":core"))
    api(project(":agent-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":agent-api")))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
