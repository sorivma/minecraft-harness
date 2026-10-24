package dev.minecraftharness.chat;

import java.util.*;

/** A bounded, lossless page into a potentially large text; offsets are UTF-16 boundaries. */
final class TextWindow {
    final List<String> lines = new ArrayList<String>();
    final List<Integer> starts = new ArrayList<Integer>();
    int end;

    TextWindow(Surface surface, String text, int start, int width, int rows) {
        int i = Math.max(0, Math.min(start, text.length()));
        if (i > 0 && i < text.length() && Character.isLowSurrogate(text.charAt(i))) i--;
        while (lines.size() < rows && i < text.length()) {
            int begin = i, pixels = 0, wordBreak = -1;
            while (i < text.length()) {
                int point = text.codePointAt(i), size = Character.charCount(point);
                if (point == '\n') {
                    i += size;
                    break;
                }
                int next = surface.textWidth(new String(Character.toChars(point)));
                if (i > begin && pixels + next > Math.max(1, width)) {
                    if (wordBreak > begin) i = wordBreak;
                    break;
                }
                pixels += next;
                i += size;
                if (Character.isWhitespace(point)) wordBreak = i;
            }
            starts.add(begin);
            lines.add(text.substring(begin, i).replace("\n", ""));
        }
        end = i;
    }
}
