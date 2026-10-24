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
        if (!bounded.isEmpty() && Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)))
            bounded = bounded.substring(0, bounded.length() - 1);
        String key = width + ":" + maxLines + ":" + bounded;
        List<String> result = cache.get(key);
        if (result != null) return result;
        TextWindow page = new TextWindow(surface, bounded, 0, width, maxLines);
        measuredCharacters += page.end;
        result = new ArrayList<String>(page.lines);
        if (result.isEmpty()) result.add("");
        if (page.end < text.length()) {
            String last = result.get(result.size() - 1);
            while (!last.isEmpty() && surface.textWidth(last + "…") > width)
                last = last.substring(0, last.offsetByCodePoints(last.length(), -1));
            result.set(result.size() - 1, last + "…");
        }
        cache.put(key, result);
        return result;
    }
}
