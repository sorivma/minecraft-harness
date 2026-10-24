package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.FakeBackend;
import dev.minecraftharness.core.WorkspaceController;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ToolDetailsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private WorkspaceController core;
    private ChatPanel panel;
    private final RecordingSurface surface = new RecordingSurface();

    @Before
    public void setup() throws Exception {
        core = new WorkspaceController(temp.newFolder(), Arrays.asList(new FakeBackend()));
        await(core.connect("fake"));
        await(core.create("fake"));
        panel = new ChatPanel(core);
    }

    private void await(CompletableFuture<?> future) {
        for (int i = 0; i < 100 && !future.isDone(); i++) core.tick(32);
        future.join();
    }

    @After
    public void close() {
        core.close();
    }

    private void draw() {
        surface.reset();
        panel.render(surface, 420, 420);
        assertEquals(0, surface.clips);
    }

    private void click(String id) {
        int[] b = panel.controlBounds(id);
        assertNotNull(id, b);
        panel.click(b[0] + 4, b[1] + 4, 0);
        draw();
    }

    private Item tool(String id, String output) {
        return new Item(
                id,
                "turn",
                Kind.TOOL,
                State.COMPLETED,
                "world · inspect",
                output,
                Collections.<String, Value>emptyMap(),
                Collections.<Resource>emptyList(),
                null);
    }

    @Test
    public void groupedToolDetailsRenderSyntaxColorsAndCopyTheDisplayedJson() {
        core.selected()
                .timeline
                .snapshot(
                        tool(
                                "one",
                                "{\"name\":\"stone\",\"count\":-12,\"ok\":true,\"extra\":null}"));
        core.selected().timeline.snapshot(tool("two", "Still working..."));
        draw();
        click("item:one");
        click("call:one");
        assertEquals("details", panel.overlay());
        assertRendered("\"name\"", JsonText.KEY);
        assertRendered("\"stone\"", JsonText.STRING);
        assertRendered("-12", JsonText.NUMBER);
        assertRendered("true", JsonText.LITERAL);
        assertRendered("null", JsonText.LITERAL);
        click("copyDetails");
        String expected =
                "{\n  \"name\": \"stone\",\n  \"count\": -12,\n  \"ok\": true,\n  \"extra\": null\n}";
        assertEquals(expected, surface.clipboard());
        click("selectDetails");
        panel.key(ChatPanel.Key.A, '\0', false, true);
        panel.key(ChatPanel.Key.C, '\0', false, true);
        assertEquals(expected, surface.clipboard());
        panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        draw();
        click("call:two");
        assertRendered("Still working...", 0xffeeeeee);
        click("copyDetails");
        assertEquals("Still working...", surface.clipboard());
    }

    @Test
    public void pagingLongJsonKeepsStringColorAndCopiesEveryLine() {
        String longString = String.join("", Collections.nCopies(500, "word "));
        core.selected()
                .timeline
                .snapshot(tool("long", "{\"message\":\"" + longString + "\",\"last\":false}"));
        draw();
        click("item:long");
        List<String> firstPage = new ArrayList<String>(surface.drawn);
        panel.key(ChatPanel.Key.PAGEDOWN, '\0', false, false);
        draw();
        int detailsStart = surface.drawn.indexOf("Select text") + 1;
        assertTrue(detailsStart > 0);
        assertTrue(
                surface.drawn.subList(detailsStart, surface.drawn.size()).stream()
                        .anyMatch(s -> s.contains("word")));
        for (int i = detailsStart; i < surface.drawn.size(); i++)
            if (surface.drawn.get(i).contains("word"))
                assertEquals(JsonText.STRING, surface.colors.get(i).intValue());
        panel.key(ChatPanel.Key.PAGEUP, '\0', false, false);
        draw();
        assertEquals(firstPage, surface.drawn);
        click("copyDetails");
        assertEquals(
                "{\n  \"message\": \"" + longString + "\",\n  \"last\": false\n}",
                surface.clipboard());
    }

    private void assertRendered(String text, int color) {
        int at = surface.drawn.indexOf(text);
        assertTrue(text + " in " + surface.drawn, at >= 0);
        assertEquals(text, color, surface.colors.get(at).intValue());
    }
}
