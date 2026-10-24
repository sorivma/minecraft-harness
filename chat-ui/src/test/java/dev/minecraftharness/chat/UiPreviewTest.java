package dev.minecraftharness.chat;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.FakeBackend;
import dev.minecraftharness.core.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Reproducible portable-renderer previews; these are not live Minecraft screenshots. */
public class UiPreviewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    static final class Canvas implements Surface {
        final BufferedImage image;
        final Graphics2D graphics;
        final Deque<Shape> clips = new LinkedList<Shape>();
        final BufferedImage ascii;
        final int[] widths = new int[128];

        Canvas(int width, int height) throws IOException {
            image = new BufferedImage(width * 3, height * 3, BufferedImage.TYPE_INT_ARGB);
            graphics = image.createGraphics();
            graphics.scale(3, 3);
            graphics.setColor(new Color(0x15232d));
            graphics.fillRect(0, 0, width, height);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            File file =
                    new File(
                            "../forge-1.7.10/build/rfg/minecraft-src/resources/assets/minecraft/textures/font/ascii.png");
            ascii = file.isFile() ? ImageIO.read(file) : null;
            for (int ch = 0; ch < 128; ch++) {
                int right = -1;
                if (ascii != null)
                    for (int x = 0; x < 8; x++)
                        for (int y = 0; y < 8; y++)
                            if ((ascii.getRGB((ch % 16) * 8 + x, (ch / 16) * 8 + y) >>> 24) > 0)
                                right = Math.max(right, x);
                widths[ch] = ch == 32 ? 4 : ascii == null ? 6 : right + 2;
            }
        }

        public int textWidth(String text) {
            int width = 0;
            for (int i = 0; i < text.length(); i++)
                width += text.charAt(i) < 128 ? widths[text.charAt(i)] : 6;
            return width;
        }

        public int lineHeight() {
            return 9;
        }

        public int textWidth(String text, int style) {
            return textWidth(text) + ((style & Surface.BOLD) != 0 ? text.length() : 0);
        }

        public void text(String text, int x, int y, int color, int style) {
            int start = x;
            for (int i = 0; i < text.length(); i++) {
                String glyph = text.substring(i, i + 1);
                java.awt.geom.AffineTransform before = graphics.getTransform();
                if ((style & Surface.ITALIC) != 0) {
                    graphics.translate(0, y);
                    graphics.shear(-0.15, 0);
                    graphics.translate(0, -y);
                }
                text(glyph, x, y, color);
                if ((style & Surface.BOLD) != 0) text(glyph, x + 1, y, color);
                graphics.setTransform(before);
                x += textWidth(glyph, style);
            }
            if ((style & Surface.STRIKETHROUGH) != 0) rectangle(start, y + 4, x, y + 5, color);
        }

        public void rectangle(int l, int t, int r, int b, int c) {
            graphics.setColor(new Color(c, true));
            graphics.fillRect(l, t, r - l, b - t);
        }

        public void text(String text, int x, int y, int color) {
            graphics.setColor(new Color(color, true));
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch < 128 && ascii != null) {
                    for (int dx = 0; dx < 8; dx++)
                        for (int dy = 0; dy < 8; dy++)
                            if ((ascii.getRGB((ch % 16) * 8 + dx, (ch / 16) * 8 + dy) >>> 24) > 0)
                                graphics.fillRect(x + dx, y + dy, 1, 1);
                    x += widths[ch];
                } else {
                    graphics.drawString(Character.toString(ch), x, y + 8);
                    x += 6;
                }
            }
        }

        public void clip(int l, int t, int r, int b) {
            clips.push(graphics.getClip());
            graphics.clipRect(l, t, Math.max(0, r - l), Math.max(0, b - t));
        }

        public void unclip() {
            graphics.setClip(clips.pop());
        }

        public String clipboard() {
            return "";
        }

        public void clipboard(String value) {}

        public void openResource(String value) {}

        public void closePanel() {}

        public void ordinaryChat() {}
    }

    private <T> T await(WorkspaceController core, CompletableFuture<T> future) {
        for (int i = 0; i < 100 && !future.isDone(); i++) core.tick(32);
        return future.join();
    }

    private void write(ChatPanel panel, String name, int width, int height) throws Exception {
        Canvas canvas = new Canvas(width, height);
        panel.render(canvas, width, height);
        File directory = new File("build/reports/ui-preview");
        directory.mkdirs();
        ImageIO.write(canvas.image, "png", new File(directory, name + ".png"));
        canvas.graphics.dispose();
    }

    private void command(WorkspaceController core, ChatPanel panel, String command)
            throws Exception {
        core.draft(core.selected(), command, command.length());
        panel.render(new Canvas(420, 360), 420, 360);
        panel.key(ChatPanel.Key.ENTER, '\0', false, false);
        for (int i = 0; i < 20; i++) core.tick(32);
    }

    @Test
    public void renderMarkdown() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (WorkspaceController core =
                new WorkspaceController(temp.newFolder(), Arrays.asList(backend))) {
            await(core, core.connect("fake"));
            await(core, core.create("fake"));
            String source =
                    "# Workshop materials\n\n**Bold**, *italic*, ~~obsolete~~ and `code`.\n\n"
                            + "| Material | Purpose | Count |\n| :--- | :---: | ---: |\n"
                            + "| **Stone** | Strong walls | 128 |\n| Glass | Windows and roof lighting | 32 |\n"
                            + "| [Wood](https://example.com) | Doors | 6 |\n\n"
                            + "> Tip: leave room for cables.\n\n1. Build the frame\n   - [x] Gather materials\n   - [ ] Place blocks\n\n"
                            + "~~~java\nint count = 128;\n// **literal markup**\n~~~\n\n"
                            + "Estimate[^1].\n\n[^1]: Includes spare blocks.";
            core.selected()
                    .timeline
                    .snapshot(
                            new Item(
                                    "markdown",
                                    "turn",
                                    Kind.ASSISTANT,
                                    State.COMPLETED,
                                    "Assistant",
                                    source,
                                    Collections.<String, Value>emptyMap(),
                                    Collections.<Resource>emptyList(),
                                    null));
            ChatPanel panel = new ChatPanel(core);
            command(core, panel, "/expand");
            write(panel, "markdown", 460, 580);
            write(panel, "markdown-narrow", 240, 660);
        }
    }

    @Test
    public void renderJsonToolDetails() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (WorkspaceController core =
                new WorkspaceController(temp.newFolder(), Arrays.asList(backend))) {
            await(core, core.connect("fake"));
            await(core, core.create("fake"));
            core.selected()
                    .timeline
                    .snapshot(
                            new Item(
                                    "json",
                                    "turn",
                                    Kind.TOOL,
                                    State.COMPLETED,
                                    "world · inspect_area",
                                    "{\"block\":\"minecraft:stone\",\"position\":{\"x\":-12,\"y\":64,\"z\":128},"
                                            + "\"loaded\":true,\"entity\":null,\"tags\":[\"solid\",\"natural\"]}",
                                    Collections.singletonMap(
                                            "arguments",
                                            Value.object(
                                                    Collections.singletonMap(
                                                            "radius", Value.number(8)))),
                                    Collections.<Resource>emptyList(),
                                    32L));
            ChatPanel panel = new ChatPanel(core);
            command(core, panel, "/expand");
            write(panel, "mcp-json-timeline", 420, 420);
            int[] bounds = panel.controlBounds("item:json");
            Assert.assertNotNull(bounds);
            panel.click(bounds[0] + 4, bounds[1] + 4, 0);
            write(panel, "mcp-json-details", 420, 420);
            write(panel, "mcp-json-details-narrow", 240, 300);
        }
    }

    @Test
    public void renderRepresentativeStates() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (WorkspaceController core =
                new WorkspaceController(temp.newFolder(), Arrays.asList(backend))) {
            await(core, core.connect("fake"));
            await(core, core.create("fake"));
            core.selected().ui.settings =
                    new Settings("Builder model", "high", "standard", "default", "workspace-write");
            ChatPanel panel = new ChatPanel(core);
            core.selected()
                    .timeline
                    .snapshot(
                            new Item(
                                    "user",
                                    "turn",
                                    Kind.USER,
                                    State.COMPLETED,
                                    "You",
                                    "Build a workshop near the river.",
                                    Collections.<String, Value>emptyMap(),
                                    Collections.<Resource>emptyList(),
                                    null));
            for (int i = 0; i < 24; i++)
                core.selected()
                        .timeline
                        .snapshot(
                                new Item(
                                        "tool-" + i,
                                        "turn",
                                        Kind.TOOL,
                                        i == 23
                                                ? State.RUNNING
                                                : i == 4 ? State.FAILED : State.COMPLETED,
                                        "world · inspect_area",
                                        "Checking the building site",
                                        Collections.<String, Value>emptyMap(),
                                        Collections.<Resource>emptyList(),
                                        null));
            core.selected().turn = new Turn("turn", State.RUNNING, null);
            core.draft(core.selected(), "Keep the entrance facing south.", 30);
            write(panel, "chat-compact", 420, 300);
            command(core, panel, "/status");
            write(panel, "status", 420, 420);
            panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
            Form.Field field =
                    new Form.Field(
                            "material",
                            "Which material should I use for the workshop?",
                            "Stone: Durable and fits the village.\nWood: Faster to build with nearby resources.",
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
                            new RequestKey(core.selected().conversation.key, "preview"),
                            "turn",
                            "tool",
                            "Choose the workshop design",
                            "",
                            new Form(
                                    Arrays.asList(
                                            field,
                                            Form.Field.text(
                                                    "size",
                                                    "How large should the workshop be?",
                                                    true))));
            backend.emit(Event.request(request));
            for (int i = 0; i < 20; i++) core.tick(32);
            core.draft(core.selected(), "Keep the entrance facing south.", 30);
            write(panel, "question", 420, 360);
            write(panel, "question-narrow", 240, 300);
            write(panel, "question-small", 240, 180);
            panel.key(ChatPanel.Key.ESCAPE, '\0', false, false);
        }
    }
}
