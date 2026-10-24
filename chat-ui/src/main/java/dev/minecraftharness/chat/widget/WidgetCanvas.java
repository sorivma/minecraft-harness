package dev.minecraftharness.chat.widget;

import dev.minecraftharness.chat.Surface;

/**
 * Local coordinates and clipped drawing, without access to chat navigation, clipboard or clip
 * stacks.
 */
public final class WidgetCanvas {
    public interface Drawing {
        void draw(int left, int top, int width, int height);
    }

    /**
     * Trusted extension drawing; coordinates are absolute logical pixels. False means unsupported.
     */
    public boolean custom(int left, int top, int w, int h, Drawing drawing) {
        int l = Math.max(0, left), t = Math.max(0, top);
        int r = (int) Math.min(width, (long) left + w);
        int b = (int) Math.min(height, (long) top + h);
        if (r <= l || b <= t) return false;
        return surface.custom(
                x + l, y + t, r - l, b - t, () -> drawing.draw(x + left, y + top, w, h));
    }

    /** Host-owned hit registration. Registration never executes the callback. */
    public interface Actions {
        void register(
                String id, String label, int x, int y, int width, int height, Runnable callback);
    }

    private final Surface surface;
    private final int x, y, width, height;
    private final Actions actions;

    public WidgetCanvas(Surface surface, int x, int y, int width, int height) {
        this(surface, x, y, width, height, null);
    }

    public WidgetCanvas(Surface surface, int x, int y, int width, int height, Actions actions) {
        this.surface = surface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.actions = actions;
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

    /**
     * An explicit user action with arbitrary trusted-mod logic. Payload parsing and drawing must
     * remain side-effect free. Callbacks run on the UI thread and must not block; an integration
     * owns its world/session checks and any asynchronous work. Disabled or clipped buttons have no
     * callback. A canvas without an action host is a display-only preview.
     */
    public void button(
            String id,
            String label,
            int left,
            int top,
            int w,
            int h,
            boolean enabled,
            Runnable callback) {
        if (id == null
                || !id.matches("[a-zA-Z0-9_.:-]{1,128}")
                || label == null
                || label.length() > 256
                || callback == null) throw new IllegalArgumentException("Invalid widget button");
        rectangle(left, top, w, h, enabled ? 0xff384e61 : 0xff30353a);
        text(label, left + 4, top + 4, enabled ? 0xffeeeeee : 0xff999999);
        int l = Math.max(0, left), t = Math.max(0, top);
        int r = (int) Math.min(width, (long) left + w);
        int b = (int) Math.min(height, (long) top + h);
        if (enabled && actions != null && r > l && b > t)
            actions.register(id, label, x + l, y + t, r - l, b - t, callback);
    }

    public void button(
            String id, String label, int left, int top, int w, int h, Runnable callback) {
        button(id, label, left, top, w, h, true, callback);
    }
}
