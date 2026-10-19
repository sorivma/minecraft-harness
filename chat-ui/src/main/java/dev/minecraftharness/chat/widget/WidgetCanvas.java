package dev.minecraftharness.chat.widget;

import dev.minecraftharness.chat.Surface;

/**
 * Local coordinates and clipped drawing, without access to chat navigation, clipboard or clip
 * stacks.
 */
public final class WidgetCanvas {
    private final Surface surface;
    private final int x, y, width, height;

    public WidgetCanvas(Surface surface, int x, int y, int width, int height) {
        this.surface = surface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int lineHeight() {
        return surface.lineHeight();
    }

    public int textWidth(String text) {
        return surface.textWidth(text);
    }

    public void text(String text, int left, int top, int color) {
        surface.text(text.replace('\u00a7', '?'), x + left, y + top, color);
    }

    public void rectangle(int left, int top, int w, int h, int color) {
        if (w > 0 && h > 0) surface.rectangle(x + left, y + top, x + left + w, y + top + h, color);
    }

    public boolean item(String id, int metadata, int count, int left, int top) {
        return surface.item(id, metadata, count, x + left, y + top);
    }
}
