package dev.minecraftharness.chat;

/** Shared geometry for painting, pointer selection and visual-line navigation. */
final class EditorView {
    final Editor editor;
    final TextLayout layout = new TextLayout();
    int x, y, w, rows, first, line;

    EditorView(Editor editor) {
        this.editor = editor;
    }

    void layout(Surface surface, int x, int y, int w, int rows, boolean secret) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.rows = rows;
        line = surface.lineHeight();
        String display = editor.text.replace('\t', ' ').replace('\u00a7', '?');
        if (secret) {
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < display.length(); i++)
                masked.append(display.charAt(i) == '\n' ? '\n' : '*');
            display = masked.toString();
        }
        layout.update(surface, display, w);
        int cursor = layout.row(editor.cursor);
        first = Math.max(0, Math.min(first, layout.lines.size() - rows));
        if (cursor < first) first = cursor;
        if (cursor >= first + rows) first = cursor - rows + 1;
    }

    int position(Surface surface, int px, int py) {
        int row = first + Math.max(-1, Math.min(rows, (py - y) / line));
        return layout.position(surface, row, px - x);
    }
}
