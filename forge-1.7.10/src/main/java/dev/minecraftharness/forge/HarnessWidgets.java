package dev.minecraftharness.forge;

import dev.minecraftharness.chat.widget.*;
import java.util.function.Function;

/**
 * Public client extension entry point. Register during mod initialization before opening a world.
 */
public final class HarnessWidgets {
    public static final int API_VERSION = WidgetRegistry.API_VERSION;
    private static final WidgetRegistry REGISTRY = WidgetRegistry.defaults();

    private HarnessWidgets() {}

    public static void register(String id, String instructions, Function<String, Widget> parser) {
        REGISTRY.register(id, instructions, parser);
    }

    /** Each world uses one consistent catalog for both rendering and agent instructions. */
    public static WidgetRegistry snapshot() {
        return REGISTRY.snapshot();
    }
}
