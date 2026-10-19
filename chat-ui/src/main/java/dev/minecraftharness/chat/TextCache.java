package dev.minecraftharness.chat;

import java.util.*;

/** Bounded LRU for visible text previews; no frame walks or wraps the whole history. */
final class TextCache {
    private final LinkedHashMap<String, List<String>> cache =
            new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) {
                    return size() > 128;
                }
            };
    int measuredCharacters;

    List<String> preview(Surface surface, String text, int width, int maxLines) {
        String bounded = text.length() > 512 ? text.substring(0, 512) : text;
        String key = width + ":" + maxLines + ":" + bounded;
        List<String> result = cache.get(key);
        if (result != null) return result;
        result = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < bounded.length() && result.size() < maxLines; ) {
            int point = bounded.codePointAt(i);
            i += Character.charCount(point);
            measuredCharacters++;
            if (point == '\n') {
                result.add(line.toString());
                line.setLength(0);
                continue;
            }
            String next = new String(Character.toChars(point));
            if (surface.textWidth(line.toString() + next) > Math.max(1, width)
                    && line.length() > 0) {
                result.add(line.toString());
                line.setLength(0);
            }
            if (result.size() < maxLines) line.append(next);
        }
        if (result.size() < maxLines) result.add(line.toString());
        cache.put(key, result);
        return result;
    }
}
