package dev.minecraftharness.chat;

/** Counts the real font work, including prose, editors, menus and extension widgets. */
final class MeasuredSurface implements Surface {
    private final Surface delegate;
    int characters;

    MeasuredSurface(Surface delegate) {
        this.delegate = delegate;
    }

    public int textWidth(String text) {
        characters += text.length();
        return delegate.textWidth(text);
    }

    public int textWidth(String text, int style) {
        characters += text.length();
        return delegate.textWidth(text, style);
    }

    public void text(String text, int x, int y, int color, int style) {
        delegate.text(text, x, y, color, style);
    }

    public int lineHeight() {
        return delegate.lineHeight();
    }

    public void rectangle(int l, int t, int r, int b, int c) {
        delegate.rectangle(l, t, r, b, c);
    }

    public void text(String s, int x, int y, int c) {
        delegate.text(s, x, y, c);
    }

    public boolean item(String id, int meta, int count, int x, int y) {
        return delegate.item(id, meta, count, x, y);
    }

    public boolean custom(int l, int t, int w, int h, Runnable draw) {
        return delegate.custom(l, t, w, h, draw);
    }

    public void clip(int l, int t, int r, int b) {
        delegate.clip(l, t, r, b);
    }

    public void unclip() {
        delegate.unclip();
    }

    public String clipboard() {
        return delegate.clipboard();
    }

    public void clipboard(String s) {
        delegate.clipboard(s);
    }

    public void openResource(String s) {
        delegate.openResource(s);
    }

    public void closePanel() {
        delegate.closePanel();
    }

    public void ordinaryChat() {
        delegate.ordinaryChat();
    }
}
