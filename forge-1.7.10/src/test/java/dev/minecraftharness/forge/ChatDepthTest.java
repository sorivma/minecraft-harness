package dev.minecraftharness.forge;

import static org.junit.Assert.*;

import dev.minecraftharness.chat.Surface;
import dev.minecraftharness.chat.widget.*;
import java.nio.ByteBuffer;
import org.junit.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/**
 * Opt-in real GPU regression: ./gradlew :forge-1.7.10:test -PrenderTests --tests '*ChatDepthTest'.
 */
public class ChatDepthTest {
    private Pbuffer buffer;

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("harness.renderTests"));
        buffer = new Pbuffer(64, 64, new PixelFormat(8, 24, 0), null, null);
        buffer.makeCurrent();
        GL11.glViewport(0, 0, 64, 64);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 64, 64, 0, -1000, 1000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glClearColor(0, 0, 0, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    @After
    public void cleanup() {
        if (buffer != null) buffer.destroy();
    }

    private void itemDepth() {
        // Inventory items write foreground depth; attribute restoration does not restore
        // depth-buffer pixels.
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 0, 0, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(8, 8, 100);
        GL11.glVertex3f(8, 24, 100);
        GL11.glVertex3f(24, 24, 100);
        GL11.glVertex3f(24, 8, 100);
        GL11.glEnd();
    }

    @Test
    public void menuBackgroundCoversForegroundItemPixels() {
        itemDepth();
        new CodexChatScreen("").rectangle(0, 0, 40, 40, 0xff00ff00);
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(16, 64 - 16, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals("Menu must cover the red item", 0, pixel.get(0) & 255);
        assertEquals("Menu must be visible", 255, pixel.get(1) & 255);
        assertTrue("Restore the host depth state", GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        assertTrue("Restore the host write mask", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
    }

    @Test
    public void recipeSlotsHaveVisibleEdgesInNativeChat() {
        final CodexChatScreen screen = new CodexChatScreen("");
        Surface surface =
                new Surface() {
                    public int textWidth(String text) {
                        return text.length() * 6;
                    }

                    public int lineHeight() {
                        return 9;
                    }

                    public void text(String text, int x, int y, int color) {}

                    public void rectangle(int l, int t, int r, int b, int color) {
                        screen.rectangle(l, t, r, b, color);
                    }

                    public boolean item(String id, int meta, int count, int x, int y) {
                        return true;
                    }

                    public void clip(int l, int t, int r, int b) {}

                    public void unclip() {}

                    public String clipboard() {
                        return "";
                    }

                    public void clipboard(String text) {}

                    public void openResource(String location) {}

                    public void closePanel() {}

                    public void ordinaryChat() {}
                };
        screen.rectangle(0, 0, 64, 64, 0xe030353a);
        String recipe =
                "```minecraft-recipe\nPlanks\nminecraft:planks|Planks|4|0\nminecraft:log|Log|1|0\n-\n-\n-\n-\n-\n-\n-\n-\n```";
        WidgetRegistry.defaults()
                .parse(recipe)
                .widgets
                .get(0)
                .render(new WidgetCanvas(surface, 0, 0, 100, 64));
        ByteBuffer edge = BufferUtils.createByteBuffer(4), center = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(20, 63, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, edge);
        GL11.glReadPixels(28, 55, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, center);
        assertTrue(
                "Empty slots need distinct edges to make the 3x3 grid visible",
                Math.abs((edge.get(0) & 255) - (center.get(0) & 255)) >= 16);
    }
}
