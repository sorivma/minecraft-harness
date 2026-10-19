# Planning widgets

Bruh can now show nine additional built-in display widgets. They use the existing
widget extension API and work in inline chat, the detail view, copied text, and
saved conversations. Reopen the world after updating the mod, then start or resume
a conversation to load the new catalog and agent instructions.

| Widget ID (prefix `minecraftharness:`) | Suggested request |
| --- | --- |
| `materials-v1` | Show a materials checklist for this build, including stacks and missing quantities. |
| `machine-v1` | Explain this machine recipe with its inputs, outputs, fluids, energy, time, and byproducts. |
| `chain-v1` | Show the intermediate crafting steps and quantities needed for this item. |
| `layer-v1` | Show a top-down block diagram of each layer, including north and the block legend. |
| `comparison-v1` | Compare these two tools in a table, including tradeoffs. |
| `production-v1` | Compare supply and demand rates and highlight production bottlenecks. |
| `waypoint-v1` | Save these supplied coordinates as a waypoint card. |
| `progression-v1` | Show milestones and their prerequisites for this project. |
| `shapeless-v1` | Show this shapeless recipe with its unordered ingredients. |

Widgets are snapshots, not subscriptions or controls. They do not inspect the
inventory, look up recipes, validate a multiblock, place markers, craft, or update
completion states. Game facts must come from available tools or the user; plans
and assumptions must be labeled as estimates. A waypoint card records coordinates
in conversation history; it does not register a waypoint with another mod.

## Payload contract

Use a fenced block with the exact widget ID. Each new format begins with a title
and one of `source|measured`, `source|user-provided`, or `source|estimated`.
The source describes the whole card; use separate cards for information with
different provenance. Unknown quantities are represented by `?` only in fields
that explicitly support it. Never interpret an unknown count as zero.

Fields are separated by literal pipes, without escaping. Titles and fields are
1–120 characters; control characters and embedded pipes are unsupported. Payloads
are limited to 16,000 characters and 32 records after the title/source lines.
Item fields use namespaced registry IDs and metadata from 0–32767. Item names
remain readable when icons cannot be resolved. Counts are integers no greater
than 1,000,000. Malformed payloads stay visible as ordinary text.

The following schemas describe records after the title and source:

* **Materials:** `itemId|label|metadata|required|have-or-?|stackSize`.
  Required is positive, have is nonnegative, and stack size is 1–64. Totals are
  shown as stacks plus remainder using the supplied stack size. Missing is
  `max(0, required - have)`; unknown have means unknown missing. Bars show coverage.
* **Machine:** first `machine|name|duration|energy`. Then item records
  `input|itemId|label|metadata|count`, `output|itemId|label|metadata|count`, or
  `byproduct|itemId|label|metadata|count|chancePercent` (0–100). Fluids use
  `fluid-in|label|amount-and-unit` or `fluid-out|label|amount-and-unit`.
  Item counts are positive. At least one input and output are required; fluids
  and byproducts count toward their corresponding sides. Duration, energy, and
  fluid quantities are supplied descriptive fields, not calculated values.
* **Chain:** `step|action|itemId|outputLabel|metadata|positiveOutputCount|dependencies`.
  State input quantities in the action. Dependencies are comma-separated earlier
  step numbers, starting at 1, or `-` for none. Counts are supplied per step;
  the widget does not recursively resolve recipes or calculate a shopping list.
* **Layer:** first `layer|integerY|north-edge`, with Y from -2048 to 2048 and north
  one of `top`, `right`, `bottom`, `left`. Define blocks with
  `legend|symbol|itemId|label|metadata`, where symbol is one ASCII letter or digit.
  Follow legends with `row|cells` records for a rectangular grid up to 16×16.
  A dot means air; every other symbol must be defined once. Rows run top to bottom,
  cells left to right. One card represents one layer. The global record limit
  includes the header, legends, and rows.
* **Comparison:** first two or three `item|itemId|label|metadata` records, followed
  by 1–16 `stat|name|value1|value2[|value3]` records. Include units in values.
  Columns follow item order. Cell text wraps using the actual game font metrics.
* **Production:** first `unit|shared-rate-unit`, followed by
  `resource|name|supplyRate|demandRate|machineCount-or-?`. Rates are nonnegative
  decimal numbers up to 1,000,000 with at most six decimal places; machine counts
  are positive when known. Supply and demand are aggregate rates for the listed
  machines, not per-machine rates. Deficits and surpluses are calculated, and
  supply below demand is highlighted as a bottleneck. No unit conversion occurs.
* **Waypoint:** exactly `location|dimension|x|y|z`, then `note|text`. Coordinates
  are integers; X/Z range from -30,000,000 to 30,000,000, Y from -2048 to 2048.
  The dimension is a supplied label; coordinates are not converted or verified.
* **Progression:** `status|milestone|dependencies`, with status `todo`, `doing`,
  `done`, or `unknown`. Dependencies are distinct earlier milestone numbers or
  `-`. These are supplied states, not interactive checkboxes. Forward references,
  self-references, and cycles are rejected for progression and crafting chains.
* **Shapeless:** one `output|itemId|label|metadata|positiveCount`, followed by 1–9
  `input|itemId|label|metadata|positiveCount` records. Ingredient order has no
  significance. This is a recipe explanation, not recipe validation.

Complete example payloads for every format are included in the registry's agent
instructions and covered by automated parse/render tests. Existing shaped recipes
retain their [original format](recipe-widgets.md).

## Composing a build guide

Ask: “Give me a build guide with a materials checklist, layer diagrams, relevant
recipes, and a progression checklist.” The agent is instructed to compose these
in that order, with numbered titles. No separate composite payload is needed.
Up to 16 widgets fit in a message; larger guides should be split across replies.
The existing message parsing limit is 32,768 characters.

For example, these cards describe an eight-block foundation using estimated
quantities and unknown inventory:

````text
Gather the materials, then place the foundation with north at the top.

```minecraftharness:materials-v1
1. Foundation materials
source|estimated
minecraft:stone|Stone|0|8|?|64
```

```minecraftharness:layer-v1
2. Foundation layer
source|estimated
layer|64|top
legend|S|minecraft:stone|Stone|0
row|SSS
row|S.S
row|SSS
```

```minecraftharness:progression-v1
3. Foundation milestones
source|estimated
todo|Gather eight stone blocks|-
todo|Place the foundation|1
```
````

All cards retain a full text representation for copying and small-screen
fallback. Visual labels wrap instead of being abbreviated. New cards do not
repeat their text fallback below the visual. Layer grids use smaller cells when
needed, with legend symbols when item icons cannot fit; extremely narrow layouts
fall back to text. Saved cards remain snapshots; ask for a fresh card after
providing updated inventory or progress.

Widgets stay between their original explanations in the message. Clicking a
visual opens that particular card. In details, Next changes both the title and
the displayed card; tall cards scroll with the wheel or PageUp/PageDown instead
of disappearing. Select text exposes the selected card's full text, while Copy
all copies the complete message with all widgets in their original order.

Validation uses isolated tests and the Forge build. In-game appearance and modpack
compatibility require user verification.
