# Widget rendering regression

The Forge surface previously let inherited OpenGL depth testing apply to 2D chat
rectangles and text. Inventory rendering writes foreground depth. Restoring GL
attributes after an item does not restore the depth buffer, so later menu pixels
could be rejected behind previously drawn items. Recipe slots also used a flat
fill almost identical to their parent chat card, without visible edges.

The surface now draws rectangles and text in painter order with depth testing and
depth writes disabled, restoring the host state afterward. Each item clears depth
only in its icon region intersected with the active clip, before rendering with
depth testing. Recipe slots have contrasting recessed borders, including empty
slots.

Run the offscreen GPU regression and full suite with Java 17:

```powershell
.\gradlew.bat test :forge-1.7.10:build -PrenderTests
```

`ChatDepthTest` creates a real LWJGL Pbuffer, seeds foreground item-like depth,
draws the actual native surface menu rectangle, and checks pixel colors and
restored depth state. A second test renders the recipe widget through native
rectangles and compares empty-slot edge and interior pixels. Both failed before
the fixes and pass afterward. These tests need graphics drivers and LWJGL natives;
ordinary test runs skip them. They do not launch a world or exercise third-party
item renderers. Keeping a GPU-level regression catches behavior the portable
recording surface cannot observe.
