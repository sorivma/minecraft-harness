package dev.minecraftharness.chat.widget;

/**
 * A display-only widget instance. Instances belong to one parsed message; never retain world state
 * globally.
 */
public interface Widget {
    String title();

    /**
     * Complete accessible/copyable representation, also used when rendering fails or space is
     * limited.
     */
    String plainText();

    /** Requested logical pixel height at the given width. Must be non-negative. */
    int height(int width);

    /** Metric-aware layout; the default preserves existing extension implementations. */
    default int height(
            int width, int lineHeight, java.util.function.ToIntFunction<String> textWidth) {
        return height(width);
    }

    /** True only when the visual contains all text, so the panel need not duplicate it below. */
    default boolean includesText() {
        return false;
    }

    /**
     * Draw within the supplied canvas. Called on the client thread; must not block or mutate the
     * world.
     */
    void render(WidgetCanvas canvas);
}
