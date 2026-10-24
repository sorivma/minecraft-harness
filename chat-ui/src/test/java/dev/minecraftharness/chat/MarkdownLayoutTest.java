package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.AgentData.Resource;
import java.util.*;
import org.junit.Test;

public class MarkdownLayoutTest {
    private final RecordingSurface surface = new RecordingSurface();

    private MarkdownLayout layout(String source, int width) {
        MarkdownLayout layout = new MarkdownLayout();
        layout.update(surface, source, width);
        return layout;
    }

    private String text(MarkdownLayout layout) {
        StringBuilder text = new StringBuilder();
        for (MarkdownLayout.Row row : layout.rows) {
            for (MarkdownLayout.Run run : row.runs) text.append(run.text);
            text.append('\n');
        }
        return text.toString();
    }

    private MarkdownLayout.Run run(MarkdownLayout layout, String text) {
        for (MarkdownLayout.Row row : layout.rows)
            for (MarkdownLayout.Run run : row.runs) if (run.text.equals(text)) return run;
        throw new AssertionError("Missing run: " + text + " in " + text(layout));
    }

    @Test
    public void tableAlignsColumnsAndKeepsInlineFormatting() {
        MarkdownLayout layout =
                layout(
                        "| Left | Center | Right |\n| :--- | :---: | ---: |\n| **bold** | x | 42 |",
                        300);
        assertEquals(Surface.BOLD, run(layout, "bold").style);
        assertTrue(run(layout, "x").x > run(layout, "Center").x);
        assertEquals(
                run(layout, "Right").x + run(layout, "Right").width,
                run(layout, "42").x + run(layout, "42").width);
        assertFalse(text(layout).contains("---"));
        assertFalse(text(layout).contains("|"));
        assertTrue(layout.rows.get(0).boxes.size() > 3);
    }

    @Test
    public void narrowTablesPreserveEveryFieldAndWideTablesWrapCells() {
        String table = "A | B | C\n---|---|---\nfirst value|second value|third value";
        MarkdownLayout narrow = layout(table, 90);
        String text = text(narrow).replace("\n", "");
        assertTrue(text.contains("Afirst valueBsecond valueCthird value"));
        String longCell = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
        MarkdownLayout wide = layout("A|B\n---|---\n" + longCell + "|end", 160);
        assertTrue(text(wide).replace("\n", "").replace("end", "").contains(longCell));
        assertBounds(narrow, 90);
        assertBounds(wide, 160);
    }

    private void assertBounds(MarkdownLayout layout, int width) {
        for (MarkdownLayout.Row row : layout.rows) {
            for (MarkdownLayout.Run run : row.runs) {
                assertTrue(run.text, run.x >= 0);
                assertTrue(
                        run.text + " ends at " + (run.x + run.width), run.x + run.width <= width);
            }
            for (MarkdownLayout.Box box : row.boxes) assertTrue(box.x + box.width <= width);
        }
    }

    @Test
    public void escapedPipesAndCodeInsideTableStayInTheirCells() {
        MarkdownLayout layout = layout("| Name | Value |\n| --- | --- |\n| a\\|b | `x\\|y` |", 300);
        assertNotNull(run(layout, "a|b"));
        assertTrue(run(layout, "x|y").code);
    }

    @Test
    public void rendersNestedStylesHeadingsListsQuotesAndRules() {
        MarkdownLayout layout =
                layout(
                        "# Heading\n\nSetext\n===\n\n**bold** *italic* ~~old~~ ***both***\n\n"
                                + "3) third\n   - nested\n     - [X] done\n   - [ ] todo\n\n> quoted\n>\n> > deep\n\n---",
                        300);
        assertEquals(Surface.BOLD, run(layout, "Heading").style);
        assertEquals(Surface.BOLD, run(layout, "Setext").style);
        assertEquals(Surface.ITALIC, run(layout, "italic").style);
        assertEquals(Surface.STRIKETHROUGH, run(layout, "old").style);
        assertEquals(Surface.BOLD | Surface.ITALIC, run(layout, "both").style);
        assertNotNull(run(layout, "3."));
        assertTrue(run(layout, "nested").x > run(layout, "third").x);
        assertTrue(run(layout, "deep").x > run(layout, "quoted").x);
        assertTrue(text(layout).contains("[x] done"));
        assertTrue(text(layout).contains("[ ] todo"));
        assertFalse(text(layout).contains("---"));
        assertBounds(layout, 300);
    }

    @Test
    public void orderedParenthesisListDoesNotUsePlainTextShortcut() {
        assertNotNull(run(layout("1) one\n2) two", 200), "1."));
    }

    @Test
    public void codeFencesAndInlineCodeNeverBecomeMarkupOrLinks() {
        String source =
                "````md\n```java\n**literal** [example](https://example.com)\n```\n````\n\n"
                        + "~~~text\n# heading\n~~~\n\n    _indented_\n\n``a ` b``";
        MarkdownLayout layout = layout(source, 500);
        assertTrue(text(layout).contains("**literal** [example](https://example.com)"));
        assertTrue(run(layout, "# heading").code);
        assertTrue(run(layout, "_indented_").code);
        assertTrue(run(layout, "a ` b").code);
        assertTrue(MessageMarkup.links(source).isEmpty());
    }

    @Test
    public void linksSupportReferencesTitlesBalancedParenthesesAndImages() {
        String source =
                "[same](https://example.com/a_(b) \"title\") [same][ref]\n\n"
                        + "[ref]: https://example.org/ref\n\n<https://example.net> https://example.edu\n\n"
                        + "![Map](https://example.com/map.png)\n\n[unsafe](javascript:alert(1)) [file](./a.java)";
        List<Resource> links = MessageMarkup.links(source);
        assertEquals(5, links.size());
        assertEquals("https://example.com/a_(b)", links.get(0).location);
        MarkdownLayout layout = layout(source, 500);
        assertTrue(text(layout).contains("Image: Map ↗"));
        assertTrue(text(layout).contains("file (./a.java)"));
        for (MarkdownLayout.Row row : layout.rows)
            for (MarkdownLayout.Run run : row.runs)
                if (run.link != null) assertTrue(run.link.startsWith("https://"));
    }

    @Test
    public void entitiesEscapesBreaksAndLiteralHtmlRemainReadable() {
        MarkdownLayout layout =
                layout(
                        "\\# literal &amp; &lt;tag&gt;\n\nfoo_bar_baz \\*literal\\*\n\n"
                                + "first<br>second  \nthird\n\n<script>alert(1)</script>",
                        500);
        assertTrue(text(layout).contains("# literal & <tag>"));
        assertTrue(text(layout).contains("foo_bar_baz *literal*"));
        assertTrue(text(layout).contains("first\nsecond\nthird"));
        assertTrue(text(layout).contains("<script>alert(1)</script>"));
    }

    @Test
    public void footnoteDefinitionsAreVisibleAndCanContainFormatting() {
        MarkdownLayout layout =
                layout("Fact[^source].\n\n[^source]: **Evidence**\n    continued", 300);
        assertTrue(text(layout).contains("[source]"));
        assertEquals(Surface.BOLD, run(layout, "[source] Evidence").style);
        assertTrue(text(layout).contains("continued"));
    }

    @Test
    public void tablesCanFollowProseWithoutBlankLineIncludingInsideQuotes() {
        MarkdownLayout layout =
                layout(
                        "Results:\n| Material | Count |\n| --- | --- |\n| Stone | 64 |\n\n"
                                + "> Quoted results:\n> | A | B |\n> | --- | --- |\n> | C | D |",
                        300);
        assertNotNull(run(layout, "Results:"));
        assertNotNull(run(layout, "Stone"));
        assertNotNull(run(layout, "Quoted results:"));
        assertNotNull(run(layout, "C"));
    }

    @Test
    public void streamedPartialSyntaxConvergesToCompletedLayoutAndResizes() {
        String source =
                "# Heading\n\nA|B\n---|---\n**bold**|[link](https://example.com)\n\n```java\nint n = 2;\n```";
        MarkdownLayout streamed = new MarkdownLayout();
        for (int i = 0; i <= source.length(); i++)
            streamed.update(surface, source.substring(0, i), 280);
        assertEquals(text(layout(source, 280)), text(streamed));
        streamed.update(surface, source, 90);
        assertEquals(text(layout(source, 90)), text(streamed));
        assertBounds(streamed, 90);
    }
}
