package dev.minecraftharness.chat;

/** Multiline Unicode editor; positions are UTF-16 boundaries, never half a surrogate pair. */
public final class Editor {
    public String text;
    public int cursor, mark;
    private final java.util.Deque<Editor> undo = new java.util.ArrayDeque<Editor>();
    private final java.util.Deque<Editor> redo = new java.util.ArrayDeque<Editor>();

    public void place(int position, boolean selecting) {
        cursor = boundary(Math.max(0, Math.min(text.length(), position)));
        if (!selecting) mark = cursor;
    }

    public void undo(boolean forward) {
        java.util.Deque<Editor> source = forward ? redo : undo, target = forward ? undo : redo;
        if (source.isEmpty()) return;
        Editor saved = new Editor(text, cursor);
        saved.mark = mark;
        target.push(saved);
        Editor old = source.pop();
        text = old.text;
        cursor = old.cursor;
        mark = old.mark;
    }

    public Editor(String text, int cursor) {
        this.text = text;
        this.cursor = boundary(Math.max(0, Math.min(cursor, text.length())));
        this.mark = this.cursor;
    }

    private int boundary(int value) {
        return value > 0 && value < text.length() && Character.isLowSurrogate(text.charAt(value))
                ? value - 1
                : value;
    }

    public void selectAll() {
        mark = 0;
        cursor = text.length();
    }

    public String selection() {
        return text.substring(Math.min(mark, cursor), Math.max(mark, cursor));
    }

    public void insert(String value) {
        Editor saved = new Editor(text, cursor);
        saved.mark = mark;
        undo.push(saved);
        if (undo.size() > 100) undo.removeLast();
        redo.clear();
        int start = Math.min(mark, cursor), end = Math.max(mark, cursor);
        text =
                text.substring(0, start)
                        + value.replace("\r\n", "\n").replace('\r', '\n')
                        + text.substring(end);
        cursor = start + value.replace("\r\n", "\n").replace('\r', '\n').length();
        mark = cursor;
    }

    public void backspace() {
        if (mark != cursor) {
            insert("");
            return;
        }
        if (cursor > 0) {
            mark = text.offsetByCodePoints(cursor, -1);
            insert("");
        }
    }

    public void delete() {
        if (mark != cursor) {
            insert("");
            return;
        }
        if (cursor < text.length()) {
            mark = text.offsetByCodePoints(cursor, 1);
            insert("");
        }
    }

    public void move(int delta, boolean selecting) {
        if (!selecting && mark != cursor)
            cursor = delta < 0 ? Math.min(cursor, mark) : Math.max(cursor, mark);
        else if (delta < 0 && cursor > 0) cursor = text.offsetByCodePoints(cursor, -1);
        else if (delta > 0 && cursor < text.length()) cursor = text.offsetByCodePoints(cursor, 1);
        if (!selecting) mark = cursor;
    }

    public void home(boolean selecting) {
        cursor = text.lastIndexOf('\n', Math.max(-1, cursor - 1)) + 1;
        if (!selecting) mark = cursor;
    }

    public void end(boolean selecting) {
        int end = text.indexOf('\n', cursor);
        cursor = end < 0 ? text.length() : end;
        if (!selecting) mark = cursor;
    }

    public void vertical(int direction, boolean selecting) {
        int start = text.lastIndexOf('\n', Math.max(-1, cursor - 1)) + 1, column = cursor - start;
        if (direction < 0 && start > 0) {
            int prior = text.lastIndexOf('\n', start - 2) + 1;
            cursor = boundary(Math.min(start - 1, prior + column));
        } else if (direction > 0) {
            int next = text.indexOf('\n', cursor);
            if (next >= 0) {
                int end = text.indexOf('\n', next + 1);
                cursor = boundary(Math.min(end < 0 ? text.length() : end, next + 1 + column));
            }
        }
        if (!selecting) mark = cursor;
    }
}
