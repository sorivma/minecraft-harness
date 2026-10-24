# Agent activity, followups and questions

With the chat closed, an active agent shows a small status line at the bottom left,
above the hotbar area: `Codex: Working (14 s)` with an animated pixel spinner.
The timer starts when a turn is submitted and continues when the chat is closed.
`Needs answer [T]` replaces the spinner when a question or approval needs input.
Additional active conversations are counted alongside the status. F1 hides the HUD.

The Codex switch sits at the right end of the vanilla chat input, separated by a
thin divider. It turns amber when any conversation needs an answer. Clicking it
opens a conversation with a pending question, if present.

Enter during a running task queues a followup for `turn/steer`. Codex incorporates
it at its next supported step boundary. Followups retain their order, including
messages entered before `turn/start` returns. Pending questions hold delivery until
the player answers. `/steer` toggles to the alternative behavior of waiting for
the entire turn to finish. `/queue` allows inspection, cancellation and retry.
Failed steering retains the message and pauses delivery for explicit retry.

New conversations register the `ask_questions` client tool in every work mode.
It accepts one to three questions with unique `id`, `question`, and optional
`options` containing `label` and `description`. Questions appear as an inline card
above the chat composer, with selectable options and their descriptions. `Other`
opens an editable answer when the backend allows free text. Previous/next controls
switch questions without losing answers; `Later` collapses the card. Only
`Submit answer` sends the complete response. The ordinary chat draft stays separate.
Native `requestUserInput` also uses this card. Nested MCP elicitation forms retain
their dedicated form editor.

Consecutive MCP/tool calls within the same turn share one activity entry. It shows
the number of calls, running/completed/failed/stopped counts, and the latest tool.
Opening it lists the original calls and their full results; grouping never changes
the backend timeline. Messages, questions and turn boundaries separate groups.
Tool details pretty-print valid JSON with two-space indentation and colors for keys,
strings, numbers and literals. Structured metadata, including arguments, is shown as
JSON below the output. Copy all and text selection use the displayed text without
color codes. Plain text and incomplete JSON remain unchanged. Outputs over 1,048,576
UTF-16 code units or over 64 levels deep remain plain text to bound formatting work.

`/status` opens a live session dashboard with connection, conversation, model,
running/next settings, permissions and workspace cards. Read commands such as
`/mcp`, `/limits`, `/usage`, `/skills` and `/plugins` present labelled cards, with
refresh and next-page controls where supported. Complete backend fields are kept
behind `Details`; absent values and usage percentages are never invented.

The composer grows from one to four lines and supports mouse positioning/selection,
visual-line arrows, and Ctrl+Z / Ctrl+Y. Reading positions survive conversation
switches. Streaming follows the bottom until the player scrolls away, then offers
`Latest` / `New events`. Background questions appear in the chat switcher.

Codex 0.163.0 registers dynamic tools at conversation creation. Use `/new` once
after updating to enable `ask_questions` in a conversation; conversations created
with the tool retain it when resumed.

Regression coverage includes followups during startup, delivery under a saturated
event stream, FIFO ordering, waiting for user input, a complete dynamic question
JSON-RPC roundtrip, and editing a suggested answer before explicit submission.
Portable renderer previews are produced by `UiPreviewTest` in
`chat-ui/build/reports/ui-preview`. They use a headless surface (with the native
ASCII texture when available), not a running game or live backend. HUD placement,
input and OpenGL compatibility still require visual checking in a running game.
