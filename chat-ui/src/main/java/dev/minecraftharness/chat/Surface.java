package dev.minecraftharness.chat;

/** Only a version adapter implements this interface. All coordinates are logical pixels. */
public interface Surface {
    int BOLD = 1, ITALIC = 2, STRIKETHROUGH = 4;

    int textWidth(String text);

    /** Adapters may render native styles; plain text is the portable fallback. */
    default int textWidth(String text, int style) {
        return textWidth(text);
    }

    int lineHeight();

    void rectangle(int left, int top, int right, int bottom, int color);

    void text(String text, int x, int y, int color);

    default void text(String text, int x, int y, int color, int style) {
        text(text, x, y, color);
        if ((style & STRIKETHROUGH) != 0)
            rectangle(
                    x,
                    y + lineHeight() / 2,
                    x + textWidth(text, style),
                    y + lineHeight() / 2 + 1,
                    color);
    }

    /** Draw a 16px item icon. False requests a portable text fallback. */
    default boolean item(String registryId, int metadata, int count, int x, int y) {
        return false;
    }

    /** Optional native drawing. Host clips the region and restores graphics state on failure. */
    default boolean custom(int left, int top, int width, int height, Runnable draw) {
        return false;
    }

    void clip(int left, int top, int right, int bottom);

    void unclip();

    String clipboard();

    void clipboard(String text);

    void openResource(String location);

    void closePanel();

    void ordinaryChat();
}
