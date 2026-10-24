package dev.minecraftharness.chat;

import java.util.*;

/** Lossless wrapping; streamed appends only remeasure the last visual line and the new tail. */
final class TextLayout {
    String source = "";
    final List<String> lines = new ArrayList<String>();
    final List<Integer> starts = new ArrayList<Integer>();
    private int width = -1;

    void update(Surface surface, String text, int width) {
        if (this.width == width && text.equals(source) && !lines.isEmpty()) return;
        int from = 0;
        if (this.width == width && text.startsWith(source) && !starts.isEmpty()) {
            from = starts.remove(starts.size() - 1);
            lines.remove(lines.size() - 1);
        } else {
            lines.clear();
            starts.clear();
        }
        TextWindow page = new TextWindow(surface, text, from, width, Integer.MAX_VALUE);
        lines.addAll(page.lines);
        starts.addAll(page.starts);
        if (lines.isEmpty() || text.endsWith("\n")) {
            lines.add("");
            starts.add(text.length());
        }
        source = text;
        this.width = width;
    }

    int row(int cursor) {
        int p = Collections.binarySearch(starts, cursor);
        return p >= 0 ? p : Math.max(0, -p - 2);
    }

    int position(Surface surface, int row, int x) {
        row = Math.max(0, Math.min(row, lines.size() - 1));
        int i = starts.get(row), end = i + lines.get(row).length(), pixels = 0;
        while (i < end) {
            int next = source.offsetByCodePoints(i, 1);
            int w = surface.textWidth(source.substring(i, next));
            if (x < pixels + (w + 1) / 2) break;
            pixels += w;
            i = next;
        }
        return i;
    }

    int vertical(Surface surface, int cursor, int direction) {
        int row = row(cursor), next = Math.max(0, Math.min(lines.size() - 1, row + direction));
        int x = surface.textWidth(source.substring(starts.get(row), cursor));
        return position(surface, next, x);
    }
}
