# Security policy

## Reporting

Use [GitHub private vulnerability reporting](https://github.com/sorivma/minecraft-harness/security/advisories/new) for vulnerabilities. Include the affected version, reproduction steps, impact and a sanitized example. Do not put credentials or a live world save in a public issue. The latest released minor line receives security fixes; there is no guaranteed response-time SLA.

## Trust boundaries

Harness launches the configured native Codex executable without a shell. The process uses a workspace under the local world save; its initial policies are `workspace-write` and `on-request`. Approvals and mandatory questions require explicit answers. Installed mods and the selected executable are trusted local code.

The world project configuration is trusted for the launched process. A downloaded world may contain a pre-existing `codex-workspace/.codex/config.toml`; review or remove that workspace before using the agent on an untrusted save. A working directory does not by itself restrict reads outside that directory.

MCP discovery can supply authorization headers. These are written into the world-local `.codex/config.toml` managed block. Keep filesystem access restricted to trusted users, redact saves/configuration before sharing and rotate credentials after accidental exposure. Configuration inspection masks credentials and environment/header maps. This does not guarantee removal of every secret from arbitrary tool output or model responses.

CI uses read-only repository permissions for checks, immutable action revisions and a checksum-pinned Gradle distribution. Release publishing uses a separate job with write access. In-game rendering and live backend behavior are not exercised by the default CI jobs.
