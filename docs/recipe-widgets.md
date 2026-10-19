# Crafting recipe widgets

Ask Bruh to explain a shaped crafting recipe and show its recipe widget. Assistant
messages containing a valid recipe block display a 3×3 grid, output icon and
quantity, and ingredient explanations directly in the conversation. Scroll the
chat to read the whole reply. Opening details is optional for Copy all and metadata.
On very small screens the ingredient list remains available even when the grid
cannot fit. Missing item icons fall back to numbered slots and ingredient labels.

Recipe guidance is supplied when creating or resuming a Codex conversation. Resume
an already connected conversation or create a new one to receive the guidance.
The widget is display-only: it does not craft, inspect inventory, or validate the
recipe against the installed modpack. Bruh must use available game tools to verify
recipes when necessary. Multiple widgets can appear inline in one message; the optional detail view also
provides Next to cycle through them. This built-in widget supports shaped
recipes. Separate built-in [machine and shapeless widgets](planning-widgets.md)
cover those recipe types; other mods can add more formats using the
[widget extension interface](widget-api.md).

The version-neutral transport is a fenced `minecraftharness:crafting-v1` block in assistant
text. It survives streaming and saved history without a separate persistence
format. The body contains exactly eleven lines: title, output, then nine input
slots in row-major order. Each stack is `registry_id|label|count|metadata`; `-`
denotes an empty input slot. Counts range from 1 to 64 and metadata from 0 to
32767. Place explanations outside the block. Incomplete or malformed blocks remain
ordinary readable text. Blocks are interpreted only in assistant messages.

Example:

````text
Place four oak planks in a square in your inventory crafting grid.

```minecraftharness:crafting-v1
Crafting table
minecraft:crafting_table|Crafting table|1|0
minecraft:planks|Oak planks|1|0
minecraft:planks|Oak planks|1|0
-
minecraft:planks|Oak planks|1|0
minecraft:planks|Oak planks|1|0
-
-
-
-
```
````

Manual game check: request the example, open its card in compact and expanded
chat, verify the four plank icons and output, scroll the explanation, copy it,
then resume the conversation and reopen the same card. Also check an unknown
mod item ID: its label should remain readable without an icon.
