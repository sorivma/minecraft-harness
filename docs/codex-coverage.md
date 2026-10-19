# Codex 0.163.0 coverage ledger

Baseline: local `codex --version` and `codex --help`, 2026-10-19.
Wire evidence: generated schemas in `agent-codex/src/test/resources/protocol/0.163.0`.
Cross-check: [official command reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli)
and [App Server](https://learn.chatgpt.com/docs/app-server).

Audit completed against the pinned executable on 2026-10-19. The saved
[capture](codex-cli-0.163.0-help.txt) includes 74 recursively discovered help pages;
all invocations use `--help`. The TUI picker observation below and official reference
supply slash and conditional entries. Slash names are executable composer commands with filtered suggestions, Up/Down,
Tab and Enter. Table menu paths describe the contextual operation reached by that command.
The permanent command toolbar has been removed; `/commands` opens the complete catalog. Availability is checked again at execution: account,
policy, platform and experimental RPC failures remain visible and preserve input.

Test keys below identify actual isolated suites, not live account-operation checks:
A = `ActionProtocolTest` (every named action's emitted request against pinned schema)
and `CodexBackendTest` (routing, capabilities, policy, inspection and external routes);
T = `BackendContractTest`, `CodexBackendTest`, `WorkspaceControllerTest`;
F = `FormTest`, `RichFlowTest`, `ChatPanelTest`;
P = `CodexBackendTest`, `ChatPanelTest` (forbidden profiles and rejected/next parameters);
V = `ChatPanelTest`, `TextWindowTest` (headless interaction and bounded rendering);
E = `CodexBackendTest` checks the complete `ExternalActions` catalog for an explained,
copyable external command. External commands are **not executed by the menu**.
These suites test shared interaction paths; they do not assert live availability of
every account-gated action. Mutating operations have explicit confirmation and form submission.

| Command | UI route | Implementation evidence / availability | Test |
|---|---|---|---|
| /new, /clear | Conversations → New | thread/start; clear view locally | T,V |
| /agents, /resume | Conversations → Search/open | thread/list, thread/resume | T |
| /subagents | Timeline → Child conversation | emitted child thread identity | T,V |
| /rename | Conversation → Rename | thread/name/set | F,T |
| /archive | Conversation → Archive | thread/archive; resolve active turn first | T |
| /delete | Conversation → Delete | thread/delete; explicit destructive confirmation | F,T |
| /fork | Conversation → Fork | thread/fork | T |
| /side, /btw | Conversation → Side conversation | thread/fork with ephemeral=true; selects returned conversation | A,T |
| /model | Model picker | model/list; turn/start.model | P,V |
| /fast | Model → Service tier | catalog serviceTiers; turn/start.serviceTier | P |
| /daybreak | Model → Access program | catalog access metadata; runtime eligibility required | P |
| /voice | Composer → Voice | localAudio attachment; live microphone uses explained external.voice host route | A,V |
| /config | Settings → Configuration | installed picker entry; config/read; confirmed scalar writes to workspace .codex/config.toml with expectedVersion | P,F |
| /plan | Mode → Plan | collaborationMode/list and turn/start | P |
| /permissions | Permissions | permissionProfile/list, configRequirements/read, turn/start | P,F |
| /approve | External → Retry guardian-denied action | public notification lacks original GuardianAssessmentEvent required by retry RPC; external.approve | A,F |
| /compact | Context → Compact | thread/compact/start | A |
| /mention | Context → File | validated workspace-relative file path form and image/audio/skill/app/link attachments | F,V |
| /init | Context → Create instructions | explicitly labelled generation prompt, not an RPC slash command | A |
| /diff | Changes | emitted turn diff plus read-only git diff/status via process/spawn, cwd fixed to world workspace | A,V |
| /review | Review | review/start; target descriptor | F,A |
| /goal | Goal | thread/goal/get, set, clear | F,A |
| /recap | Context → Summary | explicitly labelled summary prompt; preserve context | A |
| /copy | Timeline → Copy latest | local clipboard | V |
| /export | Conversation → Export | paginated history → Markdown / clipboard | T,V |
| /raw | Timeline → Plain text | local representation | V |
| /ps | Execution → Running processes | thread/backgroundTerminals/list and emitted execution cards | A |
| /stop | Execution → Stop processes | distinguish process termination from turn/interrupt | A |
| /skills | Extensions → Skills | skills/list, skills/config/write; skill input | A,F |
| /mcp | Extensions → MCP | mcpServerStatus/list, OAuth login, config/mcpServer/reload | A,F |
| /apps | Extensions → Apps | app/list, app/read, app/installed; mention input | A |
| /plugins | Extensions → Plugins | plugin/list, installed, read, install, uninstall | A,F |
| /hooks | Extensions → Hooks | hooks/list; config changes subject to requirements | P,F |
| /experimental | Settings → Features | experimentalFeature/list, enablement/set | P,F |
| /memories | Settings → Memory | memory/status, thread/memoryMode/set, memory/reset (confirmed) | P,F |
| /import | Settings → Import | externalAgentConfig/detect, import; explicit destination review | A,F |
| /status | Status | thread settings, usage events, account/rateLimits/read | A |
| /usage | Account → Usage | account/usage/read; reports credits without modifying them | A,F |
| /debug-config | Diagnostics → Configuration | config/read, configRequirements/read; redact secrets | A |
| /warnings | Diagnostics → Warnings | retained warning events | A |
| /feedback | Help → Feedback | feedback/upload only after explicit user submission | F |
| /logout | Account → Sign out | account/logout; explicit confirmation | A,F |
| /app | Help → Continue externally | explain Desktop transition before opening | E |
| /daemon | Connection | own local process lifecycle; shared daemon maintenance external | A,E |
| /setup-default-sandbox | Permissions → Windows setup | windowsSandbox/readiness, setupStart; explicit action | P,F |
| /ide | Context → Editor | no IDE host in Minecraft; explained editor transition | E |
| /keymap | Settings → Keys | local UI bindings; TUI config is a distinct external setting | V,E |
| /vim | Settings → Composer | local editing behavior; basic normal/insert editing: h/j/k/l, 0/$, x/i/a/o | V |
| /statusline, /title, /theme | Settings → Presentation | local presentation; terminal-specific settings external | V,E |
| /pets, /pet | Settings → Terminal pet | terminal pet host absent; explained CLI transition | E |
| /tui | Help → Terminal UI | terminal host absent; explained CLI transition | E |
| /exit, /quit | Close panel / Disconnect | close preserves tasks; disconnect is separately explicit | T,V |
| /pwd, /cwd | Workspace | canonical world workspace, read-only | T |
| /cd | Workspace → Change directory | **approved product exception**: world-bound; external CLI | E |
| /worktree | Workspace → Worktree | **approved product exception**: world-bound; external CLI | E |

Top-level CLI inventory from the pinned executable (aliases included):

| Command | UI route / implementation | Availability and verification |
|---|---|---|
| codex | New conversation | connect + thread/start + turn/start; T |
| agents | Conversations | thread/list; T |
| exec, e | Send | turn/start; UI stays attached; A |
| review | Review | review/start; F |
| login | Account → Sign in | account/login/start, cancel; external browser handoff; A,E |
| logout | Account → Sign out | account/logout; F |
| mcp | Extensions → MCP | status/OAuth/reload; OAuth/reload; scalar workspace config writes; server registration/removal/logout via external.mcp-config; F |
| plugin | Extensions → Plugins | installed/list/read/install/uninstall/share methods; source maintenance via external.marketplaces; F |
| app-server | Connection | local stdio child process; schema generation is developer tooling; A,E |
| remote-control | Help → Remote control | remote host lifecycle external to owned stdio process; E |
| app | Help → Desktop | explained external transition; E |
| completion | Help → Shell completion | shell host absent; external command instructions; E |
| update | Help → CLI update | external maintenance; explain interruption before transition; E |
| doctor | Diagnostics | local status plus external CLI report; E |
| sandbox | Permissions | sandbox configuration in turns; standalone shell testing external; P,E |
| debug | Diagnostics | protocol/version diagnostics; developer CLI subcommands external; E |
| apply, a | Changes → Apply cloud patch | external.cloud: no cloud task/patch RPC in pinned schema; F |
| resume | Conversations → Open | thread/resume; T |
| queue | Composer → Queue | core per-thread cancellable queue; T |
| archive | Conversations → Archive | thread/archive; T |
| delete | Conversations → Delete | thread/delete; explicit confirmation; F |
| migrate-rollouts | Help → History maintenance | external offline maintenance; E |
| unarchive | Conversations → Restore | thread/unarchive; T |
| fork | Conversations → Fork | thread/fork; T |
| cloud | Help → Cloud | external.cloud: no cloud task RPC; external CLI still requires account eligibility; E |
| exec-server | Help → Execution service | separate service host; external CLI; E |
| features | Settings → Features | experimentalFeature/list and enablement/set; P |
| help | Help | versioned coverage and interaction guide; V |

Global flags are represented by model/mode/permissions/resource/connection panels
where they affect the embedded runtime. Terminal rendering and shell completion
flags remain external. Workspace-changing flags use the approved exception.
Installed TUI picker was traversed on 2026-10-19 without sending a prompt. Observed:
agents, app, approve, archive, compact, config, copy, daybreak, delete, diff,
experimental, export, fast, fork, goal, hooks, ide, import, init, keymap, memories,
mention, model, new, permissions, plan, raw, recap, rename, resume, review, side,
skills, status, tui, vim, voice, worktree. Conditional entries are supplemented by
the official inventory above. Feature-gated entries are exposed through the catalog or an explained external route;
server rejection updates availability rather than silently emulating a command.


## Nested CLI command audit

Each entry in the recursive help capture maps to its parent row above, with these
specific nested routes. Help-only leaves use the local Help action and the saved capture.

| Nested family | Route / boundary |
|---|---|
| exec resume/fork/review | Open / Fork / Review, same thread operations as interactive chat |
| login status | Account (`account/read`) |
| mcp list/get/login | MCP server status contains tools/resources and named server status; OAuth form |
| mcp add/remove/logout | `external.mcp-config`: no named registration/deletion/logout RPC; CLI owns transport configuration and credential removal |
| plugin add/list/remove | `plugins.install`, `plugins`, `plugins.uninstall` |
| plugin marketplace add/list/upgrade/remove | `external.marketplaces`: no marketplace lifecycle RPC; plugin catalog does not imply Git-source lifecycle support |
| app-server daemon bootstrap/start/restart/update/enable-remote-control/disable-remote-control/stop/version | `external.daemon`: shared daemon lifecycle differs from the owned world process |
| app-server proxy | `external.daemon`: socket proxy for a separate shared host |
| app-server generate-ts/generate-json-schema | `external.debug`; developer tooling, also used to capture fixtures |
| remote-control start/stop/pair | `external.daemon`; no remote pairing host in this adapter |
| sandbox uninstall | `external.sandbox`; machine-wide CLI operation |
| debug models | Model menu shows catalog; raw developer output through `external.debug` |
| debug app-server send-message-v2 / prompt-input | `external.debug`; test/debug CLI hosts |
| cloud exec/status/list/apply/diff | `external.cloud`; absent from pinned conversation protocol |
| exec-server forward | `external.exec-server`; independent WebSocket service registration |
| features list/enable/disable | Features list/set; embedded setting applies to this app-server process; explicit workspace config writes can persist a scalar setting |

## Limits and verification scope

- The first adapter is Forge 1.7.10 and uses a text/resource `Surface`. Audio-file input
  is supported; live microphone streaming, terminal pets, terminal themes and IDE
  integration need their respective external hosts. The Presentation menu controls
  this panel's title/high contrast; Vim provides the documented basic bindings.
- `/cd` and `/worktree` are the explicit world-bound product exception.
- Plugin source maintenance, MCP transport registration/removal and credential
  revocation use the named external CLI paths; in-game inspection, OAuth, reload,
  plugin installation/removal/sharing and skill configuration use real RPCs.
- MCP forms support text/secret, integer/number, booleans, enums, enum multiselect and
  nested groups. Unsupported schema constraints or required request types receive an
  explicit error; they are never auto-approved. URL resources require an external-open confirmation.
- `init` and `recap` prepare clearly labelled drafts, followed by explicit Send.
- The schema test checks request structure and typed effects, not whether a real
  account permits a feature. Protocol/feature errors retain their backend reason.
- Live first-version checks and the OpenAL fix are recorded separately in
  [first-version-debugging.md](first-version-debugging.md). Other chat/coremod
  combinations and later Minecraft versions have not been certified.
