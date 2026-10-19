plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }

dependencies {
    api(project(":agent-api"))
    implementation("com.google.code.gson:gson:2.2.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":agent-api")))
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
