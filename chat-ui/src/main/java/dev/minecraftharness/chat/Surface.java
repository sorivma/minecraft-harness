package dev.minecraftharness.chat;

/** Only a version adapter implements this interface. All coordinates are logical pixels. */
public interface Surface {
    int textWidth(String text);

    int lineHeight();

    void rectangle(int left, int top, int right, int bottom, int color);

    void text(String text, int x, int y, int color);

    /** Draw a 16px item icon. False requests a portable text fallback. */
    default boolean item(String registryId, int metadata, int count, int x, int y) {
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
