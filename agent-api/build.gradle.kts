plugins {
    `java-library`
    `java-test-fixtures`
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
