package dev.minecraftharness.chat;

import java.util.*;
import java.util.regex.*;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.*;

/** Native, width-aware Markdown layout. Rows stay independently drawable for timeline clipping. */
final class MarkdownLayout {
    static final int FG = 0xffeeeeee, MUTED = 0xffb2bac2, ACCENT = 0xff8fc9ff;
    private static final int CODE = 0xff1d252d, BORDER = 0xff58636d, HEADER = 0xff344554;

    static final class Run {
        String text;
        int x, width;
        final int style, color;
        final boolean code;
        final String link;

        Run(String text, int x, int width, int style, int color, boolean code, String link) {
            this.text = text;
            this.x = x;
            this.width = width;
            this.style = style;
            this.color = color;
            this.code = code;
            this.link = link;
        }

        Run shift(int dx) {
            return new Run(text, x + dx, width, style, color, code, link);
        }
    }

    static final class Box {
        final int x, y, width, height, color;

        Box(int x, int y, int width, int height, int color) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.color = color;
        }
    }

    static final class Row {
        final List<Run> runs = new ArrayList<Run>();
        final List<Box> boxes = new ArrayList<Box>();
        int height, padding;

        Row(int height) {
            this.height = height;
        }
    }

    private static final class Span {
        final String text, link;
        final int style, color;
        final boolean code;

        Span(String text, int style, int color, boolean code, String link) {
            this.text = clean(text);
            this.style = style;
            this.color = color;
            this.code = code;
            this.link = link;
        }
    }

    final List<Row> rows = new ArrayList<Row>();
    private final TextLayout plain = new TextLayout();
    private final Map<String, Integer> glyphs = new HashMap<String, Integer>();
    private final Set<String> footnotes = new HashSet<String>();
    private static final Pattern FOOTNOTE = Pattern.compile("\\[\\^([^\\]\\s]+)\\]");
    private Surface surface;
    private String source;
    private int width, line;

    void update(Surface surface, String source, int width) {
        width = Math.max(1, width);
        if (source.equals(this.source) && width == this.width && line == surface.lineHeight())
            return;
        if (line != surface.lineHeight()) glyphs.clear();
        this.surface = surface;
        this.source = source;
        this.width = width;
        line = surface.lineHeight();
        rows.clear();
        // Preserve the incremental wrapping path for long unformatted streaming answers.
        if (plain(source)) {
            plain.update(surface, clean(source), width);
            for (String text : plain.lines) {
                Row row = new Row(line);
                row.runs.add(new Run(text, 0, width, 0, FG, false, null));
                rows.add(row);
            }
        } else {
            Node document = MessageMarkup.parse(source);
            footnotes.clear();
            document.accept(
                    new AbstractVisitor() {
                        public void visit(CustomBlock block) {
                            if (block instanceof MarkdownFootnote)
                                footnotes.add(((MarkdownFootnote) block).label);
                            visitChildren(block);
                        }
                    });
            children(document, 0);
            if (rows.isEmpty()) rows.add(new Row(line));
        }
    }

    private static boolean plain(String text) {
        if (text.startsWith("    ") || text.contains("\n    ") || text.indexOf('\t') >= 0)
            return false;
        for (int i = 0; i < text.length(); i++)
            if ("\\`*_{}[]<>#|~&!+-=.$):".indexOf(text.charAt(i)) >= 0) return false;
        return true;
    }

    private static String clean(String text) {
        return text.replace('\u00a7', '?')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\t", "    ");
    }

    private void children(Node parent, int indent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext())
            block(child, indent);
    }

    private int indent(int requested) {
        return Math.min(requested, Math.max(0, width - 48));
    }

    private void gap() {
        if (!rows.isEmpty() && rows.get(rows.size() - 1).height >= line) rows.add(new Row(4));
    }

    private void block(Node node, int inset) {
        inset = indent(inset);
        if (node instanceof Paragraph || node instanceof Heading) {
            if (node.getPrevious() != null && !(node.getParent() instanceof ListItem)) gap();
            int style = node instanceof Heading ? Surface.BOLD : 0;
            int color = node instanceof Heading ? ACCENT : FG;
            rows.addAll(flow(inlines(node, style, color, false, null), inset, width - inset));
        } else if (node instanceof FencedCodeBlock) {
            FencedCodeBlock code = (FencedCodeBlock) node;
            code(code.getLiteral(), code.getInfo(), inset);
        } else if (node instanceof IndentedCodeBlock) {
            code(((IndentedCodeBlock) node).getLiteral(), "", inset);
        } else if (node instanceof HtmlBlock) {
            // HTML is displayed literally, never executed or silently discarded.
            code(((HtmlBlock) node).getLiteral(), "HTML", inset);
        } else if (node instanceof BlockQuote) {
            gap();
            int start = rows.size();
            children(node, inset + 10);
            for (int i = start; i < rows.size(); i++) {
                Row row = rows.get(i);
                row.boxes.add(new Box(inset, 0, 2, row.height, ACCENT));
            }
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            gap();
            int number = node instanceof OrderedList ? ((OrderedList) node).getStartNumber() : 0;
            for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                String marker = node instanceof OrderedList ? number++ + "." : "•";
                Node first = item.getFirstChild();
                boolean task = first instanceof TaskListItemMarker;
                if (task) marker = ((TaskListItemMarker) first).isChecked() ? "[x] " : "[ ] ";
                int start = rows.size(), markerWidth = measure(marker, 0);
                children(item, inset + Math.max(10, markerWidth + 4));
                if (start == rows.size()) rows.add(new Row(line));
                if (!marker.isEmpty())
                    rows.get(start)
                            .runs
                            .add(0, new Run(marker, inset, markerWidth, 0, MUTED, false, null));
            }
        } else if (node instanceof ThematicBreak) {
            gap();
            Row row = new Row(line);
            row.boxes.add(new Box(inset, line / 2, width - inset, 1, BORDER));
            rows.add(row);
        } else if (node instanceof TableBlock) {
            gap();
            table(node, inset);
        } else if (node instanceof MarkdownFootnote) {
            gap();
            children(node, inset);
        } else if (!(node instanceof LinkReferenceDefinition)) children(node, inset);
    }

    private void code(String literal, String language, int inset) {
        gap();
        String title = language.trim();
        if (title.isEmpty()) title = "Code";
        else title = "Code · " + title;
        List<Row> block =
                flow(
                        Collections.singletonList(new Span(title, 0, MUTED, true, null)),
                        inset + 4,
                        Math.max(1, width - inset - 8));
        String text = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
        block.addAll(
                flow(
                        Collections.singletonList(new Span(text, 0, FG, true, null)),
                        inset + 4,
                        Math.max(1, width - inset - 8)));
        for (Row row : block) row.boxes.add(new Box(inset, 0, width - inset, row.height, CODE));
        rows.addAll(block);
        gap();
    }

    private List<Span> inlines(Node parent, int style, int color, boolean code, String link) {
        List<Span> out = new ArrayList<Span>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext())
            inline(node, style, color, code, link, out);
        return out;
    }

    private void inline(
            Node node, int style, int color, boolean code, String link, List<Span> out) {
        if (node instanceof Text) {
            String literal = ((Text) node).getLiteral();
            Matcher match = FOOTNOTE.matcher(literal);
            StringBuffer replaced = new StringBuffer();
            while (match.find())
                match.appendReplacement(
                        replaced,
                        Matcher.quoteReplacement(
                                footnotes.contains(match.group(1))
                                        ? "[" + match.group(1) + "]"
                                        : match.group()));
            match.appendTail(replaced);
            out.add(new Span(replaced.toString(), style, color, code, link));
        } else if (node instanceof Code)
            out.add(new Span(((Code) node).getLiteral(), style, color, true, link));
        else if (node instanceof SoftLineBreak || node instanceof HardLineBreak)
            out.add(new Span("\n", style, color, code, link));
        else if (node instanceof HtmlInline) {
            String html = ((HtmlInline) node).getLiteral();
            out.add(
                    new Span(
                            html.matches("(?i)<br\\s*/?>") ? "\n" : html,
                            style,
                            color,
                            code,
                            link));
        } else if (node instanceof TaskListItemMarker) {
            out.add(
                    new Span(
                            ((TaskListItemMarker) node).isChecked() ? "[x] " : "[ ] ",
                            Surface.BOLD,
                            ACCENT,
                            false,
                            null));
        } else {
            if (node instanceof StrongEmphasis) style |= Surface.BOLD;
            if (node instanceof Emphasis) style |= Surface.ITALIC;
            if (node instanceof Strikethrough) style |= Surface.STRIKETHROUGH;
            String destination =
                    node instanceof Link
                            ? ((Link) node).getDestination()
                            : node instanceof Image ? ((Image) node).getDestination() : null;
            if (destination != null) {
                link = MessageMarkup.webLink(destination);
                if (link != null) color = ACCENT;
                if (node instanceof Image) out.add(new Span("Image: ", style, MUTED, false, link));
            }
            out.addAll(inlines(node, style, color, code, link));
            if (destination != null) {
                // Paths, anchors and unsupported schemes remain readable, without a dead action.
                if (link != null) out.add(new Span(" ↗", style, color, false, link));
                else out.add(new Span(" (" + destination + ")", 0, MUTED, false, null));
            }
        }
    }

    private int measure(String text, int style) {
        int result = 0;
        for (int i = 0; i < text.length(); ) {
            int next = text.offsetByCodePoints(i, 1);
            String glyph = text.substring(i, next), key = style + ":" + glyph;
            Integer pixels = glyphs.get(key);
            if (pixels == null) {
                pixels = surface.textWidth(glyph, style);
                if (glyphs.size() < 4096) glyphs.put(key, pixels);
            }
            result += pixels;
            i = next;
        }
        return result;
    }

    private List<Row> flow(List<Span> spans, int x, int available) {
        List<Row> result = new ArrayList<Row>();
        Row row = new Row(line);
        result.add(row);
        int cursor = x, right = x + Math.max(1, available);
        for (Span span : spans) {
            for (int from = 0; from < span.text.length(); ) {
                if (span.text.charAt(from) == '\n') {
                    row = new Row(line);
                    result.add(row);
                    cursor = x;
                    from++;
                    continue;
                }
                boolean whitespace = Character.isWhitespace(span.text.charAt(from));
                int to = from + 1;
                while (to < span.text.length()
                        && span.text.charAt(to) != '\n'
                        && Character.isWhitespace(span.text.charAt(to)) == whitespace) to++;
                String token = span.text.substring(from, to);
                int tokenWidth = measure(token, span.style);
                if (!whitespace && cursor > x && cursor + tokenWidth > right) {
                    row = new Row(line);
                    result.add(row);
                    cursor = x;
                }
                for (int i = from; i < to; ) {
                    int next = span.text.offsetByCodePoints(i, 1);
                    String glyph = span.text.substring(i, next);
                    int pixels = measure(glyph, span.style);
                    if (cursor > x && cursor + pixels > right) {
                        if (whitespace && !span.code) break;
                        row = new Row(line);
                        result.add(row);
                        cursor = x;
                    }
                    append(row, span, glyph, cursor, pixels);
                    cursor += pixels;
                    i = next;
                }
                from = to;
            }
        }
        return result;
    }

    private void append(Row row, Span span, String text, int x, int width) {
        Run last = row.runs.isEmpty() ? null : row.runs.get(row.runs.size() - 1);
        if (last != null
                && last.style == span.style
                && last.color == span.color
                && last.code == span.code
                && Objects.equals(last.link, span.link)
                && last.x + last.width == x) {
            last.text += text;
            last.width += width;
        } else row.runs.add(new Run(text, x, width, span.style, span.color, span.code, span.link));
    }

    private void table(Node table, int inset) {
        List<List<TableCell>> data = new ArrayList<List<TableCell>>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext())
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                List<TableCell> cells = new ArrayList<TableCell>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext())
                    cells.add((TableCell) cell);
                data.add(cells);
            }
        if (data.isEmpty() || data.get(0).isEmpty()) return;
        int columns = data.get(0).size(), available = width - inset;
        int[] sizes = new int[columns];
        Arrays.fill(sizes, 54);
        int minimum = 0;
        for (int c = 0; c < columns; c++) {
            for (Span span : inlines(data.get(0).get(c), Surface.BOLD, ACCENT, false, null))
                for (String word : span.text.split("\\s+"))
                    sizes[c] = Math.max(sizes[c], measure(word, span.style) + 8);
            minimum += sizes[c];
        }
        if (minimum > available) {
            stackedTable(data, inset);
            return;
        }
        int remaining = available - minimum;
        int[] desired = new int[columns];
        for (List<TableCell> cells : data)
            for (int c = 0; c < Math.min(columns, cells.size()); c++) {
                int length = 8;
                for (Span span :
                        inlines(
                                cells.get(c),
                                cells.get(c).isHeader() ? Surface.BOLD : 0,
                                FG,
                                false,
                                null)) length += measure(span.text, span.style);
                desired[c] = Math.max(desired[c], length);
            }
        while (remaining > 0) {
            int best = 0;
            for (int c = 1; c < columns; c++)
                if (desired[c] - sizes[c] > desired[best] - sizes[best]) best = c;
            if (desired[best] <= sizes[best]) break;
            sizes[best]++;
            remaining--;
        }
        for (int c = 0; c < columns; c++)
            sizes[c] += remaining / columns + (c < remaining % columns ? 1 : 0);
        for (int r = 0; r < data.size(); r++) {
            List<TableCell> cells = data.get(r);
            List<List<Row>> wrapped = new ArrayList<List<Row>>();
            int count = 1;
            for (int c = 0; c < columns; c++) {
                List<Span> spans =
                        c < cells.size()
                                ? inlines(
                                        cells.get(c),
                                        r == 0 ? Surface.BOLD : 0,
                                        r == 0 ? ACCENT : FG,
                                        false,
                                        null)
                                : Collections.<Span>emptyList();
                List<Row> content = flow(spans, 0, sizes[c] - 8);
                wrapped.add(content);
                count = Math.max(count, content.size());
            }
            for (int l = 0; l < count; l++) {
                Row row = new Row(line + 4);
                row.padding = 2;
                if (r == 0) row.boxes.add(new Box(inset, 0, available, row.height, HEADER));
                if (r == 0 && l == 0) row.boxes.add(new Box(inset, 0, available, 1, BORDER));
                if (l == count - 1)
                    row.boxes.add(new Box(inset, row.height - 1, available, 1, BORDER));
                int x = inset;
                for (int c = 0; c < columns; c++) {
                    row.boxes.add(new Box(x, 0, 1, row.height, BORDER));
                    if (l < wrapped.get(c).size()) {
                        Row content = wrapped.get(c).get(l);
                        int contentWidth = 0;
                        for (Run run : content.runs)
                            contentWidth = Math.max(contentWidth, run.x + run.width);
                        TableCell.Alignment align =
                                c < cells.size() ? cells.get(c).getAlignment() : null;
                        int free = Math.max(0, sizes[c] - 8 - contentWidth);
                        int offset =
                                align == TableCell.Alignment.RIGHT
                                        ? free
                                        : align == TableCell.Alignment.CENTER ? free / 2 : 0;
                        for (Run run : content.runs) row.runs.add(run.shift(x + 4 + offset));
                    }
                    x += sizes[c];
                }
                row.boxes.add(new Box(inset + available - 1, 0, 1, row.height, BORDER));
                rows.add(row);
            }
        }
    }

    /** On narrow screens each record becomes labeled fields instead of unreadable tiny columns. */
    private void stackedTable(List<List<TableCell>> data, int inset) {
        List<TableCell> headers = data.get(0);
        for (int r = data.size() == 1 ? 0 : 1; r < data.size(); r++) {
            if (r > 1) gap();
            List<TableCell> cells = data.get(r);
            for (int c = 0; c < headers.size(); c++) {
                rows.addAll(
                        flow(
                                inlines(headers.get(c), Surface.BOLD, ACCENT, false, null),
                                inset + 4,
                                width - inset - 8));
                if (r > 0 && c < cells.size())
                    rows.addAll(
                            flow(
                                    inlines(cells.get(c), 0, FG, false, null),
                                    inset + 8,
                                    width - inset - 12));
            }
            Row separator = new Row(4);
            separator.boxes.add(new Box(inset, 2, width - inset, 1, BORDER));
            rows.add(separator);
        }
    }
}
