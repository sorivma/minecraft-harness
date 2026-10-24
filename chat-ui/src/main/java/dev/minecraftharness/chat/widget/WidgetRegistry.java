package dev.minecraftharness.chat.widget;

import java.util.*;
import java.util.function.Function;

/**
 * Client-thread registration, snapshotted for each world session. No executable payloads are loaded
 * from messages.
 */
public final class WidgetRegistry {
    public static final int API_VERSION = 1;
    private static final int MAX_MESSAGE = 32768, MAX_WIDGETS = 16;
    private final Map<String, Definition> definitions = new LinkedHashMap<String, Definition>();
    private boolean frozen;

    private static final class Definition {
        final String id, instructions;
        final Function<String, Widget> parser;

        Definition(String id, String instructions, Function<String, Widget> parser) {
            this.id = id;
            this.instructions = instructions;
            this.parser = parser;
        }
    }

    public static final class Entry {
        public final String title, text;
        private final Widget widget;
        private boolean failed;

        private Entry(Widget widget) {
            this.widget = widget;
            title = Objects.requireNonNull(widget.title());
            text = Objects.requireNonNull(widget.plainText());
            if (title.isEmpty() || title.length() > 256 || text.length() > MAX_MESSAGE)
                throw new IllegalArgumentException("Invalid widget text");
        }

        public int height(int width) {
            return height(width, 9, s -> s.codePointCount(0, s.length()) * 6);
        }

        public int height(
                int width, int lineHeight, java.util.function.ToIntFunction<String> textWidth) {
            if (failed) return 0;
            try {
                int h = widget.height(width, lineHeight, textWidth);
                if (h < 0 || h > 4096) throw new IllegalArgumentException("Invalid height");
                return h;
            } catch (RuntimeException e) {
                failed = true;
                return 0;
            }
        }

        public boolean includesText() {
            try {
                return widget.includesText();
            } catch (RuntimeException e) {
                return false;
            }
        }

        public void render(WidgetCanvas canvas) {
            if (failed) return;
            try {
                widget.render(canvas);
            } catch (RuntimeException e) {
                failed = true;
            }
        }

        /** Isolates a failing extension action just like a failing renderer. */
        public void invoke(Runnable callback) {
            if (failed) return;
            try {
                callback.run();
            } catch (RuntimeException e) {
                failed = true;
            }
        }
    }

    public static final class Part {
        public final String text;
        public final Entry widget;

        private Part(String text, Entry widget) {
            this.text = text;
            this.widget = widget;
        }
    }

    public static final class Message {
        public final String prose;
        public final List<Entry> widgets;
        public final List<Part> parts;

        private Message(String prose, List<Entry> widgets) {
            this(prose, widgets, Collections.singletonList(new Part(prose, null)));
        }

        private Message(String prose, List<Entry> widgets, List<Part> parts) {
            this.prose = prose;
            this.widgets = Collections.unmodifiableList(widgets);
            this.parts = Collections.unmodifiableList(parts);
        }

        public String plainText() {
            StringBuilder out = new StringBuilder();
            for (Part part : parts) {
                if (part.widget == null) out.append(part.text);
                else
                    out.append('\n')
                            .append(part.widget.title)
                            .append('\n')
                            .append(part.widget.text)
                            .append('\n');
            }
            return out.toString().trim();
        }
    }

    /**
     * IDs include payload version, e.g. mymod:machine-v1. Duplicate IDs fail instead of replacing
     * another mod.
     */
    public synchronized void register(
            String id, String instructions, Function<String, Widget> parser) {
        if (frozen) throw new IllegalStateException("Registry snapshot is immutable");
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Expected namespaced widget ID");
        add(id, instructions, parser);
    }

    private void add(String id, String instructions, Function<String, Widget> parser) {
        Objects.requireNonNull(parser);
        Objects.requireNonNull(instructions);
        if (instructions.trim().isEmpty() || instructions.length() > 8192)
            throw new IllegalArgumentException("Invalid widget instructions");
        if (definitions.containsKey(id))
            throw new IllegalArgumentException("Duplicate widget: " + id);
        definitions.put(id, new Definition(id, instructions, parser));
    }

    public synchronized WidgetRegistry snapshot() {
        WidgetRegistry copy = new WidgetRegistry();
        copy.definitions.putAll(definitions);
        copy.frozen = true;
        return copy;
    }

    public static WidgetRegistry defaults() {
        WidgetRegistry result = new WidgetRegistry();
        result.register(
                "minecraftharness:crafting-v1", RecipeWidget.instructions(), RecipeWidget::parse);
        PlanningWidgets.register(result);
        // Preserve previously saved recipe messages without exposing unnamespaced registration to
        // extensions.
        result.add("minecraft-recipe", RecipeWidget.instructions(), RecipeWidget::parse);
        return result;
    }

    public synchronized String instructions() {
        StringBuilder out =
                new StringBuilder(
                        "You can show widgets in assistant replies using fenced code blocks. The fence language is the exact registered widget ID. Put explanatory prose outside blocks. Only use the registered formats below; never invent IDs. Multiple widgets are allowed (up to 16 per message). Parsing text never executes actions; extension buttons require an explicit user click.\n");
        out.append(
                "For a build guide, compose materials, layer diagrams, recipes and a progression checklist in that order in one reply, with numbered titles. Keep within the message and widget limits; split large guides across replies. Built-in planning widgets are immutable snapshots. Only use live controls when an extension explicitly documents them.\n");
        for (Definition d : definitions.values())
            if (d.id.contains(":"))
                out.append("\nWidget ")
                        .append(d.id)
                        .append(":\n")
                        .append(d.instructions)
                        .append('\n');
        return out.toString();
    }

    /**
     * Parses complete top-level fences only; unknown, invalid and incomplete blocks remain literal
     * text.
     */
    public synchronized Message parse(String text) {
        List<Entry> widgets = new ArrayList<Entry>();
        List<Part> parts = new ArrayList<Part>();
        if (text.length() > MAX_MESSAGE) return new Message(text, widgets);
        String source = text.replace("\r\n", "\n");
        StringBuilder prose = new StringBuilder();
        int cursor = 0, scan = 0;
        while (scan < source.length() && widgets.size() < MAX_WIDGETS) {
            int lineEnd = source.indexOf('\n', scan);
            if (lineEnd < 0) break;
            String line = source.substring(scan, lineEnd);
            if (!line.startsWith("```")) {
                scan = lineEnd + 1;
                continue;
            }
            int fenceSize = 0;
            while (fenceSize < line.length() && line.charAt(fenceSize) == '`') fenceSize++;
            String fence = line.substring(0, fenceSize);
            int close = lineEnd + 1, after = -1;
            while (close < source.length()) {
                int e = source.indexOf('\n', close);
                if (e < 0) e = source.length();
                if (source.substring(close, e).equals(fence)) {
                    after = e < source.length() ? e + 1 : e;
                    break;
                }
                close = e + 1;
            }
            if (after < 0) break;
            Definition d = fenceSize == 3 ? definitions.get(line.substring(3)) : null;
            if (d != null)
                try {
                    Widget widget =
                            d.parser.apply(
                                    source.substring(lineEnd + 1, close).replaceFirst("\n$", ""));
                    if (widget != null) {
                        Entry entry = new Entry(widget);
                        widgets.add(entry);
                        if (scan > cursor)
                            parts.add(new Part(source.substring(cursor, scan), null));
                        parts.add(new Part(null, entry));
                        prose.append(source, cursor, scan);
                        cursor = after;
                    }
                } catch (RuntimeException ignored) {
                    /* Keep original text when an extension rejects or fails. */
                }
            scan = after;
        }
        prose.append(source, cursor, source.length());
        if (cursor < source.length()) parts.add(new Part(source.substring(cursor), null));
        return new Message(prose.toString().trim(), widgets, parts);
    }
}
