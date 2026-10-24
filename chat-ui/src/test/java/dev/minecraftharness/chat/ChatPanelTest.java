package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.FakeBackend;
import dev.minecraftharness.core.*;
import dev.minecraftharness.core.WorkspaceController.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ChatPanelTest {
    @Test
    public void questionOptionsAllowSelectionThenCustomTextWithoutSendingImplicitly() {
        Request question =
                new Request(
                        new RequestKey(core.selected().conversation.key, "custom-question"),
                        "turn",
                        "tool",
                        "Choose material",
                        "",
                        new Form(
                                Arrays.asList(
                                        new Form.Field(
                                                "material",
                                                "Material",
                                                "Choose or type your own",
                                                Form.Type.TEXT,
                                                true,
                                                Arrays.asList("Stone", "Wood"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))));
        backend.emit(Event.request(question));
        drain();
        draw();
        click("request");
        assertEquals("", panel.overlay());
        click("question:option:0");
        click("question:custom");
        assertEquals(0, backend.answers);
        click("question:material");
        panel.key(ChatPanel.Key.END, '\0', false, false);
        text(" bricks");
        draw();
        assertTrue(surface.drawn.toString().contains("Stone bricks"));
        assertEquals(0, backend.answers);
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private WorkspaceController core;
    private FakeBackend backend;
    private ChatPanel panel;
    private final RecordingSurface surface = new RecordingSurface();

    @Before
    public void setUp() throws Exception {
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

    private void drain() {
        for (int i = 0; i < 20; i++) core.tick(32);
    }

    private void draw() {
        surface.reset();
        panel.render(surface, 420, 300);
        assertEquals(0, surface.clips);
    }

    private void click(String id) {
        int[] box = panel.controlBounds(id);
        assertNotNull("Missing control " + id, box);
        panel.click(box[0] + 1, box[1] + 1, 0);
        draw();
    }

    private void text(String s) {
        for (char c : s.toCharArray()) panel.key(ChatPanel.Key.OTHER, c, false, false);
    }

    @Test
    public void extensionButtonsInvokeOnlyOnExplicitClicksInlineAndInDetails() {
        int[] calls = {0};
        dev.minecraftharness.chat.widget.WidgetRegistry registry =
                new dev.minecraftharness.chat.widget.WidgetRegistry();
        registry.register(
                "example:actions",
                "Two independent callbacks",
                body ->
                        new dev.minecraftharness.chat.widget.Widget() {
                            public String title() {
                                return "Actions";
                            }

                            public String plainText() {
                                return "Toggle and increment";
                            }

                            public int height(int width) {
                                return 28;
                            }

                            public boolean includesText() {
                                return true;
                            }

                            public void render(
                                    dev.minecraftharness.chat.widget.WidgetCanvas canvas) {
                                canvas.button("toggle", "Toggle", 0, 0, 70, 20, () -> calls[0]++);
                                canvas.button(
                                        "other", "Other", 80, 0, 70, 20, () -> calls[0] += 10);
                            }
                        });
        panel = new ChatPanel(core, registry);
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "actions",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                "```example:actions\npayload\n```",
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        assertEquals(0, calls[0]);
        click("widget:actions:0:action:toggle");
        assertEquals(1, calls[0]);
        assertEquals("", panel.overlay());
        click("item:actions");
        assertNull(panel.controlBounds("widget:actions:0:action:toggle"));
        click("detailWidget:action:other");
        assertEquals(11, calls[0]);
    }

    @Test
    public void planningDemoDetailsShowsSelectedWidgetInsteadOfUnrelatedProse() throws Exception {
        String demo =
                new String(
                        java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(
                                        getClass().getResource("/widgets/demo-reply.txt").toURI())),
                        java.nio.charset.StandardCharsets.UTF_8);
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "demo",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                demo,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        click("item:demo");
        assertTrue(
                "Materials should show its own contents",
                surface.drawn.stream().anyMatch(s -> s.contains("Oak planks")));
        click("nextWidget");
        assertTrue(
                "Layer details should display the selected diagram",
                surface.drawn.stream().anyMatch(s -> s.contains("Layer Y=64")));
        assertFalse(
                "Selected layer must not show the message introduction",
                surface
                        .drawn
                        .subList(surface.drawn.lastIndexOf("Select text") + 1, surface.drawn.size())
                        .stream()
                        .anyMatch(s -> s.contains("Here are all 10")));
        click("copyDetails");
        assertTrue(surface.clipboard().contains("Example starter progression"));
    }

    @Test
    public void planningWidgetsStayBetweenTheirExplanationsWithoutDuplicateFallback() {
        String body =
                "Before waypoint\n```minecraftharness:waypoint-v1\nWaypoint\nsource|estimated\nlocation|Overworld|0|64|0\nnote|Visible note\n```\nAfter waypoint";
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "order",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                body,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        click("expand");
        surface.reset();
        panel.render(surface, 640, 1000);
        int note = surface.drawn.indexOf("Visible note"),
                after = surface.drawn.indexOf("After waypoint");
        assertTrue(
                "Widget must remain before the following explanation", note >= 0 && after > note);
        assertEquals(
                "Rendered text must not be repeated as fallback",
                1,
                java.util.Collections.frequency(surface.drawn, "Visible note"));
    }

    @Test
    public void clickingAWidgetOpensThatCardAndTallDetailsScrollToTheEnd() {
        String body = "```minecraftharness:progression-v1\nLong checklist\nsource|estimated\n";
        for (int i = 0; i < 25; i++) body += "todo|Milestone " + i + "|-\n";
        body +=
                "```\n```minecraftharness:waypoint-v1\nSecond card\nsource|estimated\nlocation|Overworld|1|64|2\nnote|Second note\n```";
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "tall",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                body,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        click("item:tall");
        for (int i = 0; i < 50; i++) {
            panel.scroll(-1);
            draw();
        }
        assertTrue(surface.drawn.stream().anyMatch(s -> s.contains("Milestone 24")));
        click("nextWidget");
        assertTrue(surface.drawn.contains("Second note"));
        panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        draw();
        for (int i = 0; i < 100 && panel.controlBounds("widget:tall:1") == null; i++) {
            panel.scroll(-1);
            draw();
        }
        click("widget:tall:1");
        assertTrue(surface.drawn.contains("Second card"));
        assertTrue(surface.drawn.contains("Second note"));
    }

    @Test
    public void assistantRecipeOpensGridAndReadableExplanation() {
        String recipe =
                "Make planks.\n```minecraft-recipe\nOak planks\nminecraft:planks|Oak planks|4|0\n"
                        + "minecraft:log|Oak log|1|0\n-\n-\n-\n-\n-\n-\n-\n-\n```";
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "recipe",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                recipe,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        for (int i = 0; i < 10 && !surface.drawn.contains("Crafting: Oak planks"); i++) {
            panel.scroll(1);
            draw();
        }
        assertTrue(surface.drawn.contains("Crafting: Oak planks"));
        click("item:recipe");
        assertEquals("details", panel.overlay());
        assertTrue(surface.drawn.contains("Out"));
        assertTrue(surface.drawn.contains("1"));
        click("copyDetails");
        assertTrue(surface.clipboard().contains("4 x Oak planks"));
        assertTrue(surface.clipboard().contains("Make planks."));
        panel.render(surface, 120, 180);
        assertEquals(0, surface.clips);
    }

    @Test
    public void extensionWidgetsNavigateAndCopyAlongsideText() {
        dev.minecraftharness.chat.widget.WidgetRegistry registry =
                dev.minecraftharness.chat.widget.WidgetRegistry.defaults();
        registry.register(
                "test:status-v1",
                "A status label",
                body ->
                        new dev.minecraftharness.chat.widget.Widget() {
                            public String title() {
                                return body;
                            }

                            public String plainText() {
                                return "Status: " + body;
                            }

                            public int height(int width) {
                                return 20;
                            }

                            public void render(dev.minecraftharness.chat.widget.WidgetCanvas c) {
                                c.text(body, 0, 0, 0xffffffff);
                            }
                        });
        panel = new ChatPanel(core, registry);
        String body = "Explanation\n```test:status-v1\nFirst\n```\n```test:status-v1\nSecond\n```";
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "widgets",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                body,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        click("item:widgets");
        assertTrue(surface.drawn.contains("First"));
        click("nextWidget");
        assertTrue(surface.drawn.contains("Second"));
        click("copyDetails");
        assertTrue(surface.clipboard().contains("Status: First"));
        assertTrue(surface.clipboard().contains("Status: Second"));
        assertTrue(surface.clipboard().contains("Explanation"));
    }

    @Test
    public void multilineComposerDoesNotAutoApproveAndKeepsRunningWhenClosed() {
        text("First");
        panel.key(ChatPanel.Key.ENTER, '\n', true, false);
        text("Second");
        assertEquals("First\nSecond", core.selected().ui.draft);
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        assertTrue(core.selected().needsAnswer());
        assertEquals("composer", panel.focusedControl());
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        assertEquals(0, backend.answers);
        panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        assertTrue(surface.closed);
        assertTrue(core.selected().busy());
    }

    @Test
    public void inlineQuestionRequiresExplicitSubmitAndDoubleClickDoesNotResend() {
        text("Question");
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        click("request");
        assertEquals("", panel.overlay());
        click("question:submit");
        assertEquals(0, backend.answers);
        click("question:option:0");
        int[] bounds = panel.controlBounds("question:submit");
        panel.click(bounds[0] + 1, bounds[1] + 1, 0);
        panel.click(bounds[0] + 1, bounds[1] + 1, 0);
        drain();
        draw();
        assertEquals(1, backend.answers);
        assertFalse(core.selected().needsAnswer());
    }

    private void command(String value) {
        panel.key(ChatPanel.Key.A, 'a', false, true);
        text(value);
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
    }

    @Test
    public void modelCommandOpensPickerAndEscapeReturnsToComposer() {
        command("/model");
        click("model:test-model");
        assertEquals("test-model", core.selected().ui.settings.model);
        panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        draw();
        assertFalse(surface.closed);
        assertEquals("composer", panel.focusedControl());
        assertEquals("", core.selected().ui.draft);
        assertEquals(0, backend.sends);
    }

    @Test
    public void narrowLayoutAndTenThousandItemsHaveBoundedFrameWork() {
        Chat chat = core.selected();
        for (int i = 0; i < 10000; i++)
            chat.timeline.snapshot(
                    new Item(
                            "item-" + i,
                            "turn",
                            Kind.ASSISTANT,
                            State.COMPLETED,
                            "Agent",
                            "Строка " + i,
                            Collections.<String, Value>emptyMap(),
                            Collections.<Resource>emptyList(),
                            null));
        for (int i = 0; i < 99; i++) await(core.create("fake"));
        core.select(chat);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            surface.reset();
            panel.render(surface, 240, 240);
            assertTrue(panel.renderedItems <= 6);
            assertEquals(0, surface.clips);
        }
        long elapsed = System.nanoTime() - start;
        assertTrue(panel.measuredCharacters < 4096);
        assertNull(panel.controlBounds("send"));
        assertNotNull(panel.controlBounds("composer"));
        panel.scroll(1);
        String anchor = chat.ui.anchor;
        chat.timeline.snapshot(
                new Item(
                        "new",
                        "turn",
                        Kind.TOOL,
                        State.RUNNING,
                        "Tool",
                        "output",
                        Collections.<String, Value>emptyMap(),
                        Collections.<Resource>emptyList(),
                        null));
        panel.render(surface, 240, 240);
        assertEquals(anchor, chat.ui.anchor);
        assertNotNull(panel.controlBounds("latest"));
        System.out.println(
                "100 conversations / 10000 items: 100 narrow headless frames="
                        + (elapsed / 1_000_000.0)
                        + " ms; visible="
                        + panel.renderedItems
                        + "; measuredChars="
                        + panel.measuredCharacters);
    }

    @Test
    public void unicodeSelectionNeverSplitsSurrogatePairs() {
        Editor editor = new Editor("A\ud83d\ude00Б", 4);
        editor.move(-1, false);
        editor.backspace();
        assertEquals("AБ", editor.text);
        editor.selectAll();
        assertEquals("AБ", editor.selection());
        editor.insert("Мир\nСтрока");
        assertEquals(editor.text.length(), editor.cursor);
    }

    @Test
    public void firstPromptConnectsAndCreatesConversationWithoutNewButton() throws Exception {
        core.close();
        backend = new FakeBackend();
        core = new WorkspaceController(temp.newFolder(), Arrays.asList(backend));
        panel = new ChatPanel(core);
        draw();
        text("Первый запрос");
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        assertNotNull("First prompt must create a conversation", core.selected());
        assertEquals("First prompt must be sent exactly once", 1, backend.sends);
        assertEquals(1, core.chats().size());
        assertEquals("Первый запрос", core.selected().timeline.get(0).text);
        assertEquals("", core.selected().ui.draft);
    }

    @Test
    public void firstPromptKeepsEditsMadeWhileConnecting() throws Exception {
        core.close();
        backend = new FakeBackend();
        core = new WorkspaceController(temp.newFolder(), Arrays.asList(backend));
        panel = new ChatPanel(core);
        draw();
        text("First");
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        text(" next draft");
        drain();
        draw();
        assertEquals(1, backend.sends);
        assertEquals("First", core.selected().timeline.get(0).text);
        assertEquals("First next draft", core.selected().ui.draft);
    }

    @Test
    public void failedFirstConnectionKeepsTextForRetry() throws Exception {
        core.close();
        backend = new FakeBackend();
        core = new WorkspaceController(temp.newFolder(), Arrays.asList(backend));
        panel = new ChatPanel(core);
        draw();
        text("Retry me");
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        backend.close();
        drain();
        draw();
        assertNull(core.selected());
        assertEquals(0, backend.sends);
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        assertEquals(1, backend.sends);
        assertEquals("Retry me", core.selected().timeline.get(0).text);
    }

    @Test
    public void compactLayoutShowsSeveralShortRepliesAndCanReachOlderOnes() {
        Chat chat = core.selected();
        for (int i = 0; i < 10; i++)
            chat.timeline.snapshot(
                    new Item(
                            "row-" + i,
                            "turn",
                            Kind.ASSISTANT,
                            State.COMPLETED,
                            "Agent",
                            "text",
                            Collections.<String, Value>emptyMap(),
                            Collections.<Resource>emptyList(),
                            null));
        draw();
        assertTrue(panel.renderedItems > 1);
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 30; i++) {
            for (int row = 0; row < 10; row++)
                if (panel.controlBounds("item:row-" + row) != null) seen.add("row-" + row);
            panel.scroll(1);
            draw();
        }
        assertEquals(10, seen.size());
        for (int i = 0; i < 30; i++) {
            panel.scroll(-1);
            draw();
        }
        assertEquals("", chat.ui.anchor);
    }

    @Test
    public void longUserFacingMessagesAreReadableInlineWithoutOpeningDetails() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 80; i++) body.append("READABLE-ROW-").append(i).append('\n');
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "long-reply",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                body.toString(),
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        Set<String> seen = new HashSet<String>(surface.drawn);
        for (int i = 0; i < 120; i++) {
            panel.scroll(1);
            draw();
            seen.addAll(surface.drawn);
            assertEquals("", panel.overlay());
        }
        for (int i = 0; i < 80; i++)
            assertTrue("Missing row " + i, seen.contains("READABLE-ROW-" + i));
        assertTrue(surface.drawn.contains("READABLE-ROW-0"));
        for (int i = 0; i < 120; i++) {
            panel.scroll(-1);
            draw();
        }
        assertTrue(surface.drawn.contains("READABLE-ROW-79"));
    }

    @Test
    public void widgetsRenderInlineAndToolsKeepCompactPreviews() {
        String recipe =
                "```minecraft-recipe\nPlanks\nminecraft:planks|Planks|4|0\n"
                        + "minecraft:log|Log|1|0\n-\n-\n-\n-\n-\n-\n-\n-\n```";
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "inline-recipe",
                                "turn",
                                Kind.ASSISTANT,
                                State.COMPLETED,
                                "Agent",
                                recipe,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        click("expand");
        assertEquals("", panel.overlay());
        assertTrue(surface.drawn.contains("Out"));
        assertTrue(surface.drawn.contains("Output: 4 x Planks"));
        core.selected()
                .timeline
                .snapshot(
                        new Item(
                                "tool-preview",
                                "turn",
                                Kind.TOOL,
                                State.COMPLETED,
                                "Tool",
                                "first\nsecond\nHIDDEN-DETAIL",
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
        draw();
        assertFalse(surface.drawn.contains("HIDDEN-DETAIL"));
        click("item:tool-preview");
        assertEquals("details", panel.overlay());
        assertTrue(surface.drawn.contains("HIDDEN-DETAIL"));
    }

    @Test
    public void modelChangesWhileRunningOnlyAffectTheNextMessage() {
        core.selected().ui.settings = new Settings("test-model", "low", "standard", null, null);
        text("Run");
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        Settings running = core.selected().runningSettings;
        command("/model");
        click("model:test-model");
        click("effort:high");
        assertEquals("low", running.effort);
        assertEquals("high", core.selected().ui.settings.effort);
        assertSame(running, core.selected().runningSettings);
    }

    @Test
    public void composerKeepsTheEndOfLongMultilineDraftVisible() {
        StringBuilder draft = new StringBuilder();
        for (int i = 0; i < 60; i++) draft.append("line ").append(i).append('\n');
        draft.append("LAST-CURSOR");
        core.draft(core.selected(), draft.toString(), draft.length());
        draw();
        assertTrue(surface.drawn.stream().anyMatch(s -> s.contains("LAST-CURSOR")));
        panel.key(ChatPanel.Key.HOME, '\0', false, false);
        panel.key(ChatPanel.Key.BACKSPACE, '\0', false, false);
        assertFalse(core.selected().ui.draft.contains("\nLAST-CURSOR"));
    }

    @Test
    public void longStreamingOutputKeepsFrameWorkBounded() {
        Chat chat = core.selected();
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 20000; i++) output.append("long tool output ");
        chat.timeline.snapshot(
                new Item(
                        "stream",
                        "turn",
                        Kind.EXECUTION,
                        State.RUNNING,
                        "Command",
                        output.toString(),
                        Collections.<String, Value>emptyMap(),
                        Collections.<Resource>emptyList(),
                        null));
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            chat.timeline.delta("stream", " next");
            draw();
            assertTrue(panel.measuredCharacters <= 512);
            assertEquals(1, panel.renderedItems);
        }
        System.out.println(
                "Long-output benchmark: 340k chars, 100 append/render frames="
                        + ((System.nanoTime() - start) / 1_000_000.0)
                        + " ms");
        click("item:stream");
        click("copyDetails");
        assertTrue(surface.clipboard().contains("long tool output"));
        assertTrue(surface.clipboard().length() > 300000);
    }

    @Test
    public void anotherBackendUsesTheSamePanelWithoutChangingTheFirstConversation()
            throws Exception {
        core.close();
        FakeBackend second = new FakeBackend("other", 1);
        backend = new FakeBackend();
        core = new WorkspaceController(temp.newFolder(), Arrays.asList(backend, second));
        await(core.connect("fake"));
        Chat first = await(core.create("fake"));
        core.draft(first, "keep", 4);
        panel = new ChatPanel(core);
        draw();
        command("/backend");
        core.draft(first, "keep", 4);
        click("backend:other");
        drain();
        draw();
        assertEquals("other", core.selected().conversation.key.connection.backend);
        assertEquals("keep", first.ui.draft);
        text("hello");
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        assertEquals(1, second.sends);
        assertEquals(0, backend.sends);
    }

    @Test
    public void cliLayoutKeepsHostControlsAndRemovesCommandToolbar() {
        panel.render(surface, 120, 240);
        assertNotNull(panel.controlBounds("chat"));
        assertNotNull(panel.controlBounds("expand"));
        for (String id :
                Arrays.asList(
                        "connect",
                        "new",
                        "models",
                        "actions",
                        "send",
                        "stop",
                        "sendMode",
                        "conversations")) assertNull(id, panel.controlBounds(id));
    }

    @Test
    public void clearStartsConversationWithoutSendingCommandOrStoppingOldTurn() {
        command("hello");
        Chat old = core.selected();
        assertTrue(old.busy());
        command("/clear");
        assertNotSame(old, core.selected());
        assertTrue(old.busy());
        assertEquals(2, core.chats().size());
        assertEquals(1, backend.sends);
    }

    @Test
    public void unknownSlashIsRetainedAndNeverSent() {
        command("/definitely-unknown");
        assertEquals(0, backend.sends);
        assertEquals("/definitely-unknown", core.selected().ui.draft);
        assertTrue(surface.drawn.stream().anyMatch(t -> t.contains("Unknown command")));
    }

    @Test
    public void slashCompletionEscapeAndKeyboardModelSelection() {
        text("/mod");
        draw();
        assertNotNull(panel.controlBounds("slash:model"));
        panel.key(ChatPanel.Key.TAB, '\0', false, false);
        assertEquals("/model", core.selected().ui.draft);
        panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        draw();
        assertNull(panel.controlBounds("slash:model"));
        assertFalse(surface.closed);
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        draw();
        assertEquals("models", panel.overlay());
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        draw();
        assertEquals("test-model", core.selected().ui.settings.model);
        assertEquals(0, backend.sends);
    }

    @Test
    public void slashArgumentsRenameAndStopUseRealOperations() {
        command("/rename My new title");
        assertEquals("My new title", core.selected().conversation.title);
        command("work");
        assertTrue(core.selected().busy());
        command("/stop");
        assertFalse(core.selected().busy());
        assertEquals(1, backend.sends);
    }

    @Test
    public void unavailableMcpCommandDoesNotBecomePrompt() {
        command("/mcp");
        assertEquals(0, backend.sends);
        assertTrue(surface.drawn.stream().anyMatch(t -> t.contains("unavailable")));
    }

    @Test
    public void slashClickTakesPriorityOverTimelineAndArrowChoiceExecutes() {
        command("hello");
        command("/stop");
        text("/mod");
        draw();
        click("slash:model");
        assertEquals("models", panel.overlay());
        panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        draw();
        text("/");
        panel.key(ChatPanel.Key.DOWN, '\0', false, false);
        draw();
        panel.key(ChatPanel.Key.ENTER, '\n', false, false);
        drain();
        draw();
        assertEquals(2, core.chats().size());
        assertEquals(1, backend.sends);
    }
}
