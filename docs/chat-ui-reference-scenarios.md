# Portable chat UI acceptance scenes

Exception, 2026-10-19: the user explicitly authorized autonomous live debugging of
the first version, including its first prompt and crash. The default restriction
below still applies to subsequent versions. Record observed results separately.

These are expected states, not screenshots or claims of passed acceptance.
Live game/backend validation belongs to the user. Agents must not run these scenes
in the game, automate its UI or collect acceptance screenshots unless the user
explicitly requests that later. These scenes are product references, not agent
runtime tasks. Isolated fake-backend/headless tests remain permitted.
Use a deterministic backend with workspace `world-A`, threads `build` and `search`.
Native font, four-pixel spacing, charcoal translucent panels and text-labelled
states apply to all scenes. Expected layouts include compact and expanded modes at
several GUI scales, a narrow window and Cyrillic input. Never replace missing runtime
data with invented progress or reasoning.

| Scene | Input/event sequence | Required visible state and interaction |
|---|---|---|
| Empty | Connect; create `build` | workspace, model, New/Actions, focused empty composer; no fake messages |
| Streaming | user text; item started; three text deltas; final snapshot | one updating answer; final text replaces deltas; draft untouched |
| Web | search query; open URL; find text; final sources | distinct labelled activities, details, clickable supplied sources |
| Tool | MCP start with arguments; progress; success or error | name/server/arguments, running then terminal state; expandable result |
| Execution | command/cwd; long output; exit code | live output, copy, stable scroll while more output arrives |
| Diff | two file changes and unified diff | per-file change status, expandable diff, draft remains visible |
| Question | two fields: choices and free text | labels, validation next to field, explicit Submit; Enter in composer never answers |
| Approval | command request in background `search` | attention badge; exact request details; Allow/Deny; double-click sends once |
| Error | model rejection, then disconnect during tool | original prompt/model retained; error and disconnected states, disabled old requests |
| Catalog | 100 conversations, several pages | search/open/rename/archive/restore; background running and unread indicators |
| Model menu | catalog with distinct effort/tier sets | only catalog choices; switch while running marked for next message |
| Recovery | reconnect; reload overlapping history page | no repeated turn execution, no duplicate items, old-generation events ignored |

Full flow: open chat → select backend/new conversation → select model → submit
“Найди пример и подготовь изменение” → inspect supplied web/tool/output/diff
events → explicitly answer question and approval → read result. No control command
is typed. The scenario includes a concurrent second conversation and its background request.

Keyboard checks: Enter sends, Shift+Enter inserts newline, Tab traverses visible
controls, arrows navigate menus, Escape closes the top overlay before the panel.
Switching conversations and closing/reopening preserve draft, cursor and reading
anchor. Closing the panel does not stop work. Ordinary game chat receives none of
the agent text. Links and external actions explain their destination.

Performance acceptance: 100 catalog entries, 10,000 stored timeline items and long
streamed output. Record tick/frame work counters and timings, paging calls and
rendered item count. Verify bounded work per tick/frame and no all-history wrapping
on each frame. Agent measurements come from isolated headless tests; live game
results are recorded only when supplied by the user.
