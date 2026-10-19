package dev.minecraftharness.chat;

import java.util.*;

public final class RecordingSurface implements Surface {
    public final List<String> drawn = new ArrayList<String>();
    public final List<String> opened = new ArrayList<String>();
    public int rectangles, clips;
    public boolean closed, gameChat;
    private String clipboard = "";

    public int textWidth(String s) {
        return s.codePointCount(0, s.length()) * 6;
    }

    public int lineHeight() {
        return 9;
    }

    public void rectangle(int l, int t, int r, int b, int color) {
        if (r < l || b < t) throw new AssertionError("Inverted rectangle");
        rectangles++;
    }

    public void text(String text, int x, int y, int color) {
        drawn.add(text);
    }

    public void clip(int l, int t, int r, int b) {
        clips++;
    }

    public void unclip() {
        clips--;
        if (clips < 0) throw new AssertionError("Unbalanced clip");
    }

    public String clipboard() {
        return clipboard;
    }

    public void clipboard(String text) {
        clipboard = text;
    }

    public void openResource(String location) {
        opened.add(location);
    }

    public void closePanel() {
        closed = true;
    }

    public void ordinaryChat() {
        gameChat = true;
    }

    public void reset() {
        drawn.clear();
        rectangles = 0;
    }
}
