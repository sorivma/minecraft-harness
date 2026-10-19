# Adding widgets from another mod

Minecraft Harness exposes a client-side Java extension interface, version 1.
Mods can register new display widgets without modifying the harness, chat panel,
or Codex backend. The built-in crafting widget uses this same interface.

The built-in [planning widgets](planning-widgets.md) also use this interface:
materials, machine recipes, crafting chains, build layers, comparisons, production
balances, waypoints, progression, and shapeless recipes. Their IDs and payload
schemas are documented there. No API version change is required.

## Registration and lifecycle

Compile against the harness development JAR (`-dev.jar`) with a compile-only
dependency. Do not bundle another copy of harness classes in your mod JAR.
Declare a Forge dependency such as `required-after:minecraftharness` and register
from your client initialization handler, before opening a world:

```java
import dev.minecraftharness.forge.HarnessWidgets;
import dev.minecraftharness.chat.widget.Widget;
import dev.minecraftharness.chat.widget.WidgetCanvas;

public final class EnergyWidget implements Widget {
    private final int percent;

    private EnergyWidget(int percent) { this.percent = percent; }

    // Call from FMLInitializationEvent on the client only.
    public static void register() {
        HarnessWidgets.register(
            "examplemod:energy-v1",
            "Show an energy gauge. Payload: one integer from 0 to 100, with no units. "
                + "Example payload: 75. Only use measured or user-provided values.",
            payload -> {
                int value = Integer.parseInt(payload.trim());
                return value < 0 || value > 100 ? null : new EnergyWidget(value);
            });
    }

    public String title() { return "Stored energy"; }
    public String plainText() { return percent + "% energy stored"; }
    public int height(int width) { return 26; }

    public void render(WidgetCanvas canvas) {
        canvas.rectangle(0, 0, canvas.width(), 10, 0xff30353a);
        canvas.rectangle(0, 0, canvas.width() * percent / 100, 10, 0xff6fca87);
        canvas.text(plainText(), 0, 14, 0xffeeeeee);
    }
}
```

The registry snapshots at world-session creation. Both the chat panel and backend
receive that same catalog. Later registrations apply when the world is reopened;
they do not change an active session. Duplicate IDs and unnamespaced IDs throw an
exception at registration. `HarnessWidgets.API_VERSION` exposes the interface
version. Keep the payload version in the ID; register both old and new IDs when
you need incompatible formats and want saved messages to keep rendering.

## Assistant output and persistence

Each registration's instructions are included in new and resumed conversations.
They should explain the payload format, validation rules, and a short example.
The parser receives only the body of a complete fence whose language matches the
registered ID exactly:

````text
The battery has enough energy for the next operation.
```examplemod:energy-v1
75
```
````

A parser returns a new `Widget` or `null` for an unsupported payload. It may parse
any text format, including JSON using the mod's own dependencies. No shared JSON
library or game-version-specific data model is imposed by the harness. Parsers
must validate their own field types, ranges, lengths, and schema versions.

Multiple widgets per assistant message render directly in the scrollable chat.
Opening details is optional; **Next** cycles through visuals there. The text and **Copy all**
include the surrounding prose and all widget text. User messages and tool output
are not interpreted as widgets. The original assistant text is persisted by the
backend, so history does not serialize Java instances. If a mod is removed, its
unknown blocks remain readable as literal text.

## Rendering contract

`Widget` supplies a title, complete plain-text representation, preferred height,
and renderer. Text and rectangles use logical pixels and ARGB colors. The canvas
origin is the upper-left corner of the widget. `item` draws a 16-pixel item icon
using a registry ID, metadata, and count; it returns false when an icon is
unavailable. Provide a readable alternative in that case.

The optional default method `height(width, lineHeight, textWidth)` allows widgets
to measure wrapped text using the same font as the renderer. Existing extensions
continue to use `height(width)` unchanged. Override `includesText()` to return
true only when the visual contains all the text: the panel then omits the duplicate
text below a successfully laid-out visual. The default is false so existing
extensions and shaped recipes retain their supplemental explanations.

Rendering is clipped to the widget rectangle. The canvas deliberately has no
clipboard, navigation, or clip-stack operations. Return height zero to use only
the text representation at a particular width. Heights above 4096 or below zero
disable the visual. Inline widgets scroll through the viewport with the message. In the optional
detail overlay, tall widgets scroll inside the available space; width-constrained
widgets can return zero height to show text instead. Widgets currently display information; this interface does not
dispatch clicks, craft items, run commands, or grant access to game tools.

All parser and rendering callbacks execute on the client thread. Keep them fast,
non-blocking, and free of world mutations. Return fresh instances and keep them
independent of current-world objects. The panel caches at most 32 parsed messages
per session. Titles and fallback text are captured once; use immutable payload
data rather than treating these widgets as live world subscriptions.

## Failure behavior and limits

Unknown formats, incomplete streaming blocks, null parser results, and parser
runtime exceptions retain the original text. A renderer or height callback runtime
exception disables that instance's visual, retaining its text. JVM errors and
non-terminating mod callbacks are not isolated: installed Java mods remain trusted
code. Model text is data and cannot register or load executable widget code.

Parsing is limited to messages of 32,768 characters and 16 successful widgets per
message. Remaining blocks stay literal text. Each title is limited to 256
characters, each fallback text to 32,768, and registration instructions to 8,192.
Only complete top-level triple-backtick blocks are interpreted; blocks inside
longer backtick example fences are left alone. The `minecraft-recipe` legacy ID
continues to render, while new guidance uses `minecraftharness:crafting-v1`.

The portable entry point is `WidgetRegistry`: instantiate or use `defaults()`,
register extensions, and pass a snapshot to `ChatPanel`. A non-Forge host can
supply `registry.instructions()` to its backend without depending on Minecraft.
For tests, use `RecordingSurface` from the chat-ui test fixtures. The registry and
panel tests include a custom gauge, multiple widgets, invalid payloads, renderer
failures, limits, snapshots, and backward-compatible recipe rendering.
