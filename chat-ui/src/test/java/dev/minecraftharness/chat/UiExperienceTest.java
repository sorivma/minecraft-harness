package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.FakeBackend;
import dev.minecraftharness.core.*;
import dev.minecraftharness.core.WorkspaceController.Chat;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class UiExperienceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private WorkspaceController core;
    private FakeBackend backend;
    private ChatPanel panel;
    private final RecordingSurface surface = new RecordingSurface();

    @Before
    public void setup() throws Exception {
        backend = new FakeBackend();
        core = new WorkspaceController(temp.newFolder(), Arrays.asList(backend));
        await(core.connect("fake"));
        await(core.create("fake"));
        panel = new ChatPanel(core);
        draw();
    }

    @After
    public void close() {
        core.close();
    }

    private <T> T await(CompletableFuture<T> f) {
        for (int i = 0; i < 100 && !f.isDone(); i++) core.tick(32);
        return f.join();
    }

    private void draw() {
        surface.reset();
        panel.render(surface, 420, 300);
    }

    private void click(String id) {
        int[] b = panel.controlBounds(id);
        assertNotNull(id, b);
        panel.click(b[0] + 4, b[1] + 4, 0);
        draw();
    }

    private void command(String text) {
        core.draft(core.selected(), text, text.length());
        draw();
        panel.key(ChatPanel.Key.ENTER, '\0', false, false);
        for (int i = 0; i < 20; i++) core.tick(32);
        draw();
    }

    private Item item(String id, String turn, Kind kind, State state, String text) {
        return new Item(
                id,
                turn,
                kind,
                state,
                kind == Kind.TOOL ? "MCP · world.inspect" : "Assistant",
                text,
                Collections.<String, Value>emptyMap(),
                Collections.<Resource>emptyList(),
                null);
    }

    private void longAnswer() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 80; i++) text.append("ROW-").append(i).append('\n');
        core.selected()
                .timeline
                .snapshot(item("answer", "turn", Kind.ASSISTANT, State.RUNNING, text.toString()));
        draw();
    }

    @Test
    public void markdownTableRendersCellsWithoutDelimiterSyntax() {
        core.selected()
                .timeline
                .snapshot(
                        item(
                                "table",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "| Material | Count |\n| :--- | ---: |\n| Stone | 64 |"));
        draw();
        assertTrue(
                "Table header must be a separate cell: " + surface.drawn,
                surface.drawn.contains("Material"));
        assertTrue(surface.drawn.contains("Stone"));
        assertTrue(surface.drawn.contains("64"));
        assertFalse(surface.drawn.toString().contains("---:"));
    }

    @Test
    public void tableLinksKeepTheirOwnTargetsAndCopyKeepsOriginalMarkdown() {
        String source =
                "| First | Second |\n| --- | --- |\n"
                        + "| [same](https://example.com/one) | [same](https://example.org/two) |";
        core.selected()
                .timeline
                .snapshot(item("table-links", "turn", Kind.ASSISTANT, State.COMPLETED, source));
        draw();
        click("link:table-links:1:1");
        assertEquals("confirm", panel.overlay());
        assertTrue(surface.opened.isEmpty());
        click("confirm");
        assertEquals(Collections.singletonList("https://example.org/two"), surface.opened);
        click("composer");
        command("/copy");
        assertEquals(source, surface.clipboard());
        assertEquals(0, surface.clips);
    }

    @Test
    public void mousePlacesCaretAndArrowsFollowWrappedLines() {
        core.draft(core.selected(), "abcdef", 6);
        draw();
        click("composer");
        panel.key(ChatPanel.Key.OTHER, 'X', false, false);
        assertEquals("Xabcdef", core.selected().ui.draft);
        String text = String.join("", Collections.nCopies(150, "x"));
        core.draft(core.selected(), text, 130);
        draw();
        panel.key(ChatPanel.Key.UP, '\0', false, false);
        assertTrue(core.selected().ui.cursor < 130);
    }

    @Test
    public void streamingFollowsTailAndSwitchingPreservesReadingPosition() {
        longAnswer();
        assertTrue(surface.drawn.contains("ROW-79"));
        core.selected().timeline.delta("answer", "ROW-80\n");
        draw();
        assertTrue(surface.drawn.contains("ROW-80"));
        for (int i = 0; i < 5; i++) {
            panel.scroll(1);
            draw();
        }
        List<String> before = new ArrayList<>();
        for (String row : surface.drawn) if (row.startsWith("ROW-")) before.add(row);
        Chat original = core.selected();
        await(core.create("fake"));
        draw();
        core.select(original);
        draw();
        List<String> after = new ArrayList<>();
        for (String row : surface.drawn) if (row.startsWith("ROW-")) after.add(row);
        assertEquals(before, after);
        core.selected().timeline.delta("answer", "ROW-81\n");
        draw();
        assertFalse(surface.drawn.contains("ROW-81"));
        assertNotNull(panel.controlBounds("latest"));
    }

    @Test
    public void compactInputGrowsInsteadOfAlwaysTakingThreeLines() {
        int initial = panel.controlBounds("composer")[3];
        core.draft(core.selected(), "first\nsecond\nthird", 18);
        draw();
        assertTrue(panel.controlBounds("composer")[3] > initial);
    }

    @Test
    public void selectionIsVisibleAndPointerSelectionSupportsCopyUndoAndRedo() {
        core.draft(core.selected(), "abcdef", 6);
        draw();
        int before = surface.rectangles;
        panel.key(ChatPanel.Key.A, '\0', false, true);
        draw();
        assertTrue(surface.rectangles > before);
        int[] input = panel.controlBounds("composer");
        panel.click(input[0] + 4, input[1] + 4, 0);
        panel.drag(input[0] + 22, input[1] + 4);
        panel.release();
        panel.key(ChatPanel.Key.C, '\0', false, true);
        assertEquals("abc", surface.clipboard());
        panel.key(ChatPanel.Key.X, '\0', false, true);
        assertEquals("def", core.selected().ui.draft);
        panel.key(ChatPanel.Key.Z, '\0', false, true);
        assertEquals("abcdef", core.selected().ui.draft);
        panel.key(ChatPanel.Key.Y, '\0', false, true);
        assertEquals("def", core.selected().ui.draft);
        assertEquals(0, backend.sends);
    }

    @Test
    public void consecutiveToolsCollapseAndFailuresRemainVisible() {
        for (int i = 0; i < 30; i++)
            core.selected()
                    .timeline
                    .snapshot(
                            item(
                                    "tool-" + i,
                                    "turn",
                                    Kind.TOOL,
                                    i == 4 ? State.FAILED : State.COMPLETED,
                                    "output " + i));
        draw();
        assertEquals(1, panel.renderedItems);
        assertTrue(surface.drawn.toString().contains("30 calls"));
        assertTrue(surface.drawn.toString().contains("1 failed"));
        click("item:tool-0");
        assertEquals("activity", panel.overlay());
        assertTrue(surface.drawn.toString().contains("world.inspect"));
        assertNotNull(panel.controlBounds("call:tool-0"));
    }

    @Test
    public void statusIsAnInteractiveDashboard() {
        core.selected().ui.settings = new Settings("test-model", "high", "standard", null, null);
        command("/status");
        assertEquals("dashboard", panel.overlay());
        assertTrue(surface.drawn.toString().contains("Connection"));
        assertTrue(surface.drawn.toString().contains("test-model"));
        assertNotNull(panel.controlBounds("dashboard:models"));
    }

    @Test
    public void backgroundRequestsAreVisibleWhileReadingAnotherChat() {
        Chat first = core.selected(), second = await(core.create("fake"));
        core.select(first);
        draw();
        backend.emit(
                Event.request(
                        new Request(
                                new RequestKey(second.conversation.key, "q"),
                                "turn",
                                "tool",
                                "Choose material",
                                "",
                                new Form(
                                        Arrays.asList(
                                                Form.Field.text("answer", "Answer", true))))));
        for (int i = 0; i < 20; i++) core.tick(32);
        draw();
        assertTrue(surface.drawn.toString().contains("1 needs answer"));
        click("conversations");
        assertTrue(surface.drawn.toString().contains("Needs answer"));
    }

    @Test
    public void streamedProseOnlyMeasuresTheChangedTail() {
        String text = String.join("", Collections.nCopies(10000, "word "));
        core.selected()
                .timeline
                .snapshot(item("long", "turn", Kind.ASSISTANT, State.RUNNING, text));
        draw();
        assertTrue(panel.measuredCharacters >= text.length());
        for (int i = 0; i < 20; i++) {
            core.selected().timeline.delta("long", " next");
            draw();
            assertTrue("Font work: " + panel.measuredCharacters, panel.measuredCharacters < 1500);
        }
    }

    @Test
    public void groupedToolUpdatesPreserveCountsAndRespectMessageBoundaries() {
        for (int i = 0; i < 10; i++)
            core.selected().timeline.snapshot(item("a-" + i, "turn", Kind.TOOL, State.RUNNING, ""));
        draw();
        core.selected()
                .timeline
                .snapshot(item("a-4", "turn", Kind.TOOL, State.FAILED, "Failure details"));
        draw();
        assertTrue(surface.drawn.toString().contains("10 calls"));
        assertTrue(surface.drawn.toString().contains("9 running"));
        assertTrue(surface.drawn.toString().contains("1 failed"));
        core.selected()
                .timeline
                .snapshot(
                        item("explanation", "turn", Kind.ASSISTANT, State.COMPLETED, "Next stage"));
        for (int i = 0; i < 4; i++)
            core.selected()
                    .timeline
                    .snapshot(item("b-" + i, "turn", Kind.TOOL, State.COMPLETED, ""));
        command("/expand");
        assertNotNull(panel.controlBounds("item:a-0"));
        assertNotNull(panel.controlBounds("item:explanation"));
        assertNotNull(panel.controlBounds("item:b-0"));
        click("item:a-0");
        click("call:a-4");
        assertTrue(surface.drawn.toString().contains("Failure details"));
    }

    @Test
    public void multipleInlineQuestionsKeepAnswersAndChatDraftSeparate() {
        Form.Field material =
                new Form.Field(
                        "material",
                        "Choose a material",
                        "",
                        Form.Type.TEXT,
                        true,
                        Arrays.asList("Stone", "Wood"),
                        null,
                        null,
                        null,
                        null,
                        null);
        Request request =
                new Request(
                        new RequestKey(core.selected().conversation.key, "inline"),
                        "turn",
                        "tool",
                        "Two questions",
                        "",
                        new Form(
                                Arrays.asList(
                                        material, Form.Field.text("size", "How large?", true))));
        backend.emit(Event.request(request));
        for (int i = 0; i < 20; i++) core.tick(32);
        core.draft(core.selected(), "My unsent task", 14);
        draw();
        assertEquals("", panel.overlay());
        click("question:option:0");
        click("question:next");
        click("question:size");
        panel.key(ChatPanel.Key.OTHER, '5', false, false);
        draw();
        click("question:previous");
        click("question:custom");
        assertTrue(surface.drawn.contains("Stone"));
        assertEquals("My unsent task", core.selected().ui.draft);
        assertEquals(0, backend.answers);
        click("question:minimize");
        assertNotNull(panel.controlBounds("request"));
        click("request");
        click("question:next");
        assertTrue(surface.drawn.contains("5"));
        panel.key(ChatPanel.Key.ENTER, '\0', false, false);
        assertEquals(0, backend.answers);
        click("question:previous");
        click("question:minimize");
        click("request");
        panel.key(ChatPanel.Key.DOWN, '\0', false, false);
        draw();
        panel.key(ChatPanel.Key.ENTER, '\0', false, false);
        draw();
        click("question:custom");
        assertTrue(surface.drawn.contains("Wood"));
        assertEquals(0, backend.answers);
    }
}
