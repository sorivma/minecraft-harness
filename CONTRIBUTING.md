# Contributing

Use small, focused pull requests with a concrete before/after description. Open an issue for substantial changes to platform support or the agent protocol.

## Development

- Run Gradle with JDK 17 or 21; compile and test with its Java 8 toolchain.
- The Windows native-argument regression uses the OS .NET Framework C# compiler
  to create a Unicode-safe argv echo fixture; no game or Codex process is needed.
- Format with `./gradlew spotlessApply`.
- Verify with `./gradlew spotlessCheck check build releaseBundle`.
- Keep Minecraft, Forge and LWJGL dependencies in `forge-1.7.10`; `verifyArchitecture` enforces the portable boundaries.
- Keep wire protocol details in `agent-codex` and use the checked-in protocol fixtures when changing requests.
- Add regression coverage for behavioral fixes. Do not require a real Codex login, network account or game launch in ordinary tests.
- Do not commit world saves, tokens, `.codex/`, machine paths, IDE state or generated build outputs.

Use Conventional Commit titles (`feat:`, `fix:`, `docs:`, `build:`, `ci:`, `chore:`); mark intentional breaking changes with `!` and explain migration in the changelog. See [VERSIONING.md](VERSIONING.md).

`runClient` uses a disposable world and deletes the development saves directory on launch. Never store personal saves there. GPU checks require `-PrenderTests` and a suitable graphics environment; report them separately from headless unit tests.

Please be respectful, explain disagreements in technical terms and avoid harassment or personal attacks. Security vulnerabilities should follow [SECURITY.md](SECURITY.md), rather than a public bug report.
