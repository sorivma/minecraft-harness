# 0.1.0 release review

Scope: the current repository, including previously uncommitted workspace-path and compatibility-warning fixes. Review focused on process/configuration boundaries, release identity, module architecture and distributable artifacts.

## Findings resolved

| Finding | Resolution |
| --- | --- |
| Configuration inspection could display MCP authorization headers, custom header credentials, `api_key` and bearer/refresh token variants | Normalize credential keys; mask header/environment maps; add an isolated backend regression test |
| The backend announced version 0.2.0 while the mod/build were 0.1.0 | Generate a shared `BuildVersion` from `gradle.properties`; expand mod metadata from the same value |
| Builds had no release automation, complete artifact manifest or wrapper checksum | Add pinned CI/release workflows, artifact validation, deterministic archive/checksums and Gradle distribution checksum |
| A clean checkout resolved RFG transforms of project JARs before those JARs were built | Filter external Markdown dependency components before resolving shaded library files |
| Machine-local `.codex` backup was eligible for accidental publication | Ignore the entire local configuration directory and backup files |

## Verification

Local JDK 17 Gradle run: `spotlessCheck check build releaseBundle`. JUnit XML reports contain 138 tests, zero failures, zero errors and three skipped opt-in graphics tests. The release packaging script verifies every expected JAR and refuses missing or unexpected assets. GitHub workflow YAML was checked with actionlint.

Linux/Windows GitHub Actions and release provenance are recorded by the workflows on the published commits. Minecraft, real GPU rendering and a live Codex backend were not launched for this review. The OpenAL coremod and compatibility with other chat/core mods still require runtime testing for each supported modpack. This review is not an exhaustive security audit.
