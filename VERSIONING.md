# Versioning and releases

Minecraft Harness follows [Semantic Versioning 2.0.0](https://semver.org/), with one version shared by every module. `gradle.properties` is the source of truth. Minecraft's `1.7.10` in artifact names identifies the platform, not the Harness version.

## Compatibility

- **Patch** (`0.1.1`): compatible bug fixes and release/build corrections.
- **Minor** (`0.2.0`): features; before 1.0, a minor release may also change the API or saved workspace format. Document migrations explicitly.
- **Major** (`1.0.0`, `2.0.0`): stable API milestones and subsequent breaking changes.
- Prereleases use SemVer suffixes such as `0.2.0-rc.1` and are marked as prereleases on GitHub.

A tag `vX.Y.Z` identifies an immutable release commit. Never move a published tag or replace published binary assets; fix mistakes with a new patch release. Portable modules, runtime mod and developer JAR always share a version. Codex protocol compatibility and Minecraft platform support are documented independently.

## Release procedure

1. Update `version` in `gradle.properties`, `CHANGELOG.md`, and `docs/releases/<version>.md`. Update installation examples when appropriate.
2. Run `./gradlew spotlessCheck check build releaseBundle` and `python scripts/prepare-release.py --version <version>`.
3. Merge through the required checks and confirm the default branch is green. Record separate runtime/GPU validation honestly; a headless build does not establish game compatibility.
4. Create an annotated tag on that commit: `git tag -a v<version> -m "Release <version>"`, then push it.
5. The release workflow verifies the tag against `gradle.properties`, rebuilds and tests, collects every distributable JAR, creates the bundle and SHA-256 manifest, and publishes a GitHub release. Inspect the workflow conclusion and assets before announcing it.

Each release includes the Forge runtime and dev JARs, four portable library JARs, source JARs, notices, release notes, an archive and `SHA256SUMS`. Consumers of integration APIs should pin both the release tag and checksum. There is currently no Maven Central publication; use the GitHub release assets.
