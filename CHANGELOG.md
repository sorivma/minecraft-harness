# Changelog

Changes are grouped by release. Versions follow [SemVer](VERSIONING.md).

## [0.1.1] - 2026-10-25

### Fixed
- Preserve quoted configuration arguments when Java launches native Codex on Windows.
  Previously the world trust override lost its quotes, causing Codex to exit with
  `invalid type ... expected a map in projects` and Harness to report
  `Codex process disconnected` before initialization.
- Cover the actual child-process argument boundary with a regression test, including
  spaced, dotted and Unicode world paths, apostrophes and escaped backslashes.

## [0.1.0] - 2026-10-25

### Added
- Forge 1.7.10 Codex chat tab with world-local workspaces and multiple conversations.
- Streaming Markdown, recipe/planning widgets, activity HUD, approvals and questions.
- Portable agent contract, world controller, chat renderer and Codex backend modules.
- Versioned Forge IMC discovery for optional MCP mods and a public widget extension API.
- Linux build, Windows portable tests, release automation, checksums and source artifacts.

### Fixed
- Dotted and spaced workspace paths in process-local Codex trust configuration.
- Compatibility warnings for CLI versions outside the checked-in 0.163.0 fixtures.
- Early connection diagnostics in the workspace controller.
- Credential redaction for MCP headers, API keys, token variants and environment maps.
- Build, mod metadata and backend identity now use one release version.

[0.1.1]: https://github.com/sorivma/minecraft-harness/releases/tag/v0.1.1
[0.1.0]: https://github.com/sorivma/minecraft-harness/releases/tag/v0.1.0
