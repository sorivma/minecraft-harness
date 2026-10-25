# Third-party notices

The Forge mod embeds CommonMark Java 0.21.0 and its GFM tables, strikethrough, task list and autolink extensions (BSD 2-Clause; Atlassian Pty Ltd and contributors), plus autolink-java (MIT; Robin Stocker). Their license texts are retained in the distributed JAR under `META-INF/` and `META-INF/licenses/`.

The Codex adapter uses Gson 2.2.4 (Apache License 2.0), supplied by the Minecraft runtime rather than bundled. Portable `agent-codex` consumers must supply their own compatible Gson dependency. Minecraft, Forge and Codex are separate products and are not included in release archives. RetroFuturaGradle, Gradle, Spotless and JUnit are build/test dependencies, not shipped game assets.

The portable library JARs do not bundle their transitive dependencies. See each module's Gradle build file for exact dependency declarations.
