# First-version debugging — 2026-10-19

The user explicitly authorized autonomous runtime debugging for this first version.
The general user-owned runtime policy remains in force for subsequent versions.

## Reported failures and fixes

- The initial composer rejected messages until New was clicked. A failing headless
  regression reproduced it. Sending now connects, creates and sends once; another
  Enter while startup is pending is ignored. Edits during startup and failed
  connection retries retain the draft. All three regressions pass.
- Codex previously opened a separate GuiScreen. It now shares a GuiChat subclass
  with vanilla chat. Switching tabs does not replace the screen. Vanilla input,
  command prefixes and its draft remain independent. Agent input is intercepted.
  The compact panel is 180 logical pixels high and bounded by native chat width.
- Both original crash reports failed in AL10.nalGetSourcei, including one before
  entering a world. Vanilla SoundManager can start two Sound Library Loader
  threads before its loaded flag is set. The Forge transformer waits for the
  existing loader to finish before returning from startup, so the next resource
  reload performs cleanup before another initialization. It does not mute sound.
- Live account/state/moderation bookkeeping had appeared as errors or conversation
  cards, hiding the answer. These notifications no longer displace messages; usage
  is retained separately and MCP startup failures still produce warnings.
- A one-card compact viewport skipped the middle message when scrolling. A failing
  regression demonstrated it; scrolling now uses the actual visible item count.

## Observed runtime evidence

Forge 1.7.10 development client, vanilla resource pack, local DEBUG WORLD,
Codex 0.163.0, 856×512 window (including chrome):

- Two client launches completed with sequential sound startup/cleanup, without the
  earlier duplicate OpenAL context exception. Master and all sound categories
  remained at 1.0.
- At 14:19:34 resource packs were reapplied through Options → Resource Packs → Done.
  The log recorded the resource reload and Sound engine started at 14:19:35.
  The client remained usable and subsequently entered the world.
- First prompts were sent without Connect or New. Both completed; the second live
  answer was visibly “Hi! What would you like to work on?” in an Agent card.
- Opening with `/` preserved the vanilla command prefix. Switching Chat → Codex →
  Chat preserved `/` in vanilla and `hi` in the agent composer, without sending the
  prompt into ordinary game chat.
- No new crash report appeared. The original reports are timestamped 14:05:11 and
  14:08:44. The successful runs began at 14:14 and 14:18.

The final notification filter additions and compact-scroll correction were covered
by automated regressions after these live observations. They were not separately
replayed in a third live run. Automated Unicode text injection was not received by
LWJGL; live prompts used physical key events. Cyrillic remains covered by headless
tests, not claimed as a passed live keyboard check here.

## Build and limits

`gradlew test :forge-1.7.10:build` passed: 30 tests across five modules, architecture
verification and reobfuscation. OpenSpec strict validation passed. The distributable
JAR includes the coremod manifest entry and sound transformer.

The sound fix waits during audio initialization/resource reload. Compatibility
with other transformers of SoundManager and third-party chat replacements has not
been tested. Full CLI parity and the remaining OpenSpec tasks are still unfinished.
