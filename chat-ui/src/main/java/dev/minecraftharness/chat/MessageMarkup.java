package dev.minecraftharness.chat;

import dev.minecraftharness.agent.AgentData.Resource;
import java.net.URI;
import java.util.*;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Shared syntax tree for display and link discovery; code is never interpreted as markup. */
final class MessageMarkup {
    private static final Parser PARSER =
            Parser.builder()
                    .extensions(
                            Arrays.asList(
                                    TablesExtension.create(), StrikethroughExtension.create(),
                                    TaskListItemsExtension.create(), AutolinkExtension.create()))
                    .customBlockParserFactory(new MarkdownFootnote.Factory())
                    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                    .build();

    static Node parse(String source) {
        Node document = PARSER.parse(source);
        if (!source.contains("|")) return document;
        // Backport the GFM behavior added after the last Java 8 release: a table may
        // immediately follow prose. Only paragraph spans are examined, never code/HTML.
        final String[] lines = source.split("\\r\\n|\\r|\\n", -1);
        final Map<Integer, String> breaks = new TreeMap<Integer, String>();
        document.accept(
                new AbstractVisitor() {
                    public void visit(Paragraph paragraph) {
                        List<SourceSpan> spans = paragraph.getSourceSpans();
                        for (int i = 2; i < spans.size(); i++) {
                            SourceSpan header = spans.get(i - 1), separator = spans.get(i);
                            String first = content(header), second = content(separator);
                            if (first.contains("|")
                                    && second.contains("|")
                                    && second.matches("[ \\t|:\\-]+")
                                    && PARSER.parse(first + "\n" + second).getFirstChild()
                                            instanceof TableBlock)
                                breaks.put(
                                        header.getLineIndex(),
                                        lines[header.getLineIndex()].substring(
                                                0, header.getColumnIndex()));
                        }
                    }

                    private String content(SourceSpan span) {
                        return lines[span.getLineIndex()].substring(
                                span.getColumnIndex(), span.getColumnIndex() + span.getLength());
                    }
                });
        if (breaks.isEmpty()) return document;
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) normalized.append('\n');
            if (breaks.containsKey(i)) normalized.append(breaks.get(i)).append('\n');
            normalized.append(lines[i]);
        }
        return PARSER.parse(normalized.toString());
    }

    static String webLink(String destination) {
        try {
            URI uri = new URI(destination);
            if (("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) return destination;
        } catch (Exception ignored) {
            // Unsupported destinations remain visible, but never become external actions.
        }
        return null;
    }

    static String label(Node node) {
        final StringBuilder out = new StringBuilder();
        node.accept(
                new AbstractVisitor() {
                    public void visit(Text text) {
                        out.append(text.getLiteral());
                    }

                    public void visit(Code code) {
                        out.append(code.getLiteral());
                    }

                    public void visit(SoftLineBreak br) {
                        out.append(' ');
                    }

                    public void visit(HardLineBreak br) {
                        out.append(' ');
                    }
                });
        return out.toString();
    }

    static List<Resource> links(String source) {
        final Map<String, Resource> resources = new LinkedHashMap<String, Resource>();
        parse(source)
                .accept(
                        new AbstractVisitor() {
                            private void add(Node node, String destination) {
                                if (webLink(destination) != null)
                                    resources.putIfAbsent(
                                            destination,
                                            new Resource(
                                                    Resource.Type.LINK, label(node), destination));
                            }

                            public void visit(Link link) {
                                add(link, link.getDestination());
                            }

                            public void visit(Image image) {
                                add(image, image.getDestination());
                            }
                        });
        return new ArrayList<Resource>(resources.values());
    }
}
