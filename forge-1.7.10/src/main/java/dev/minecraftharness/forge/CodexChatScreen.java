package dev.minecraftharness.forge;

import dev.minecraftharness.chat.*;
import java.awt.Desktop;
import java.net.URI;
import java.util.*;
import net.minecraft.client.gui.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/** One native game-chat host; the agent tab consumes its own input before vanilla chat. */
public final class CodexChatScreen extends GuiChat implements Surface {
    private final Deque<int[]> clips = new ArrayDeque<int[]>();
    private final net.minecraft.client.renderer.entity.RenderItem itemRenderer =
            new net.minecraft.client.renderer.entity.RenderItem();
    private float scale = 1;
    private ChatPanel panel;
    private boolean agent;

    public CodexChatScreen(String initial) {
        super(initial);
    }

    public void initGui() {
        String draft = inputField == null ? null : inputField.getText();
        super.initGui();
        if (draft != null) inputField.setText(draft);
        panel = ClientHarness.panel;
    }

    public boolean doesGuiPauseGame() {
        return false;
    }

    public void onGuiClosed() {
        if (panel != null) panel.closed();
        super.onGuiClosed();
    }

    protected void keyTyped(char character, int code) {
        if (!agent) {
            super.keyTyped(character, code);
            return;
        }
        if (panel == null) {
            if (code == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(null);
            return;
        }
        ChatPanel.Key key = ChatPanel.Key.OTHER;
        switch (code) {
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                key = ChatPanel.Key.ENTER;
                break;
            case Keyboard.KEY_ESCAPE:
                key = ChatPanel.Key.ESCAPE;
                break;
            case Keyboard.KEY_TAB:
                key = ChatPanel.Key.TAB;
                break;
            case Keyboard.KEY_LEFT:
                key = ChatPanel.Key.LEFT;
                break;
            case Keyboard.KEY_RIGHT:
                key = ChatPanel.Key.RIGHT;
                break;
            case Keyboard.KEY_UP:
                key = ChatPanel.Key.UP;
                break;
            case Keyboard.KEY_DOWN:
                key = ChatPanel.Key.DOWN;
                break;
            case Keyboard.KEY_HOME:
                key = ChatPanel.Key.HOME;
                break;
            case Keyboard.KEY_END:
                key = ChatPanel.Key.END;
                break;
            case Keyboard.KEY_PRIOR:
                key = ChatPanel.Key.PAGEUP;
                break;
            case Keyboard.KEY_NEXT:
                key = ChatPanel.Key.PAGEDOWN;
                break;
            case Keyboard.KEY_BACK:
                key = ChatPanel.Key.BACKSPACE;
                break;
            case Keyboard.KEY_DELETE:
                key = ChatPanel.Key.DELETE;
                break;
            case Keyboard.KEY_A:
                key = ChatPanel.Key.A;
                break;
            case Keyboard.KEY_C:
                key = ChatPanel.Key.C;
                break;
            case Keyboard.KEY_V:
                key = ChatPanel.Key.V;
                break;
            case Keyboard.KEY_X:
                key = ChatPanel.Key.X;
                break;
            default:
                break;
        }
        panel.key(key, character, isShiftKeyDown(), isCtrlKeyDown());
    }

    protected void mouseClicked(int x, int y, int button) {
        if (!agent) {
            if (button == 0 && x >= 4 && x < 74 && y >= height - 36 && y < height - 16) {
                agent = true;
                if (panel != null) panel.activated();
                return;
            }
            super.mouseClicked(x, y, button);
            return;
        }
        if (panel != null) panel.click((int) (x / scale), (int) (y / scale), button);
    }

    public void handleMouseInput() {
        if (!agent) {
            super.handleMouseInput();
            return;
        }
        // GuiChat's wheel handling scrolls ordinary messages; keep agent input isolated.
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (Mouse.getEventButtonState()) mouseClicked(x, y, Mouse.getEventButton());
        int wheel = Mouse.getEventDWheel();
        if (panel != null && wheel != 0) panel.scroll(wheel > 0 ? 1 : -1);
    }

    public void drawScreen(int x, int y, float partialTicks) {
        if (!agent) {
            super.drawScreen(x, y, partialTicks);
            drawRect(4, height - 36, 74, height - 16, 0xd0202428);
            drawString(fontRendererObj, "Codex", 12, height - 30, 0x8fc9ff);
            return;
        }
        if (panel != ClientHarness.panel) panel = ClientHarness.panel;
        if (panel == null) {
            agent = false;
            super.drawScreen(x, y, partialTicks);
            drawString(fontRendererObj, ClientHarness.unavailable, 8, height - 48, 0xffffff);
            return;
        }
        scale = Math.max(0.3f, mc.gameSettings.chatScale);
        panel.opacity(mc.gameSettings.chatOpacity * 0.9f + 0.1f);
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1);
        try {
            panel.render(
                    this,
                    Math.min((int) (width / scale), mc.ingameGUI.getChatGUI().func_146228_f() + 8),
                    (int) (height / scale));
        } finally {
            clips.clear();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glPopMatrix();
        }
    }

    public int textWidth(String text) {
        return fontRendererObj.getStringWidth(text);
    }

    public int lineHeight() {
        return fontRendererObj.FONT_HEIGHT;
    }

    /** Chat layers use painter order; inventory depth must never reject a later menu or text. */
    private void paint2d(Runnable draw) {
        GL11.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);
            draw.run();
        } finally {
            GL11.glPopAttrib();
        }
    }

    public void rectangle(int left, int top, int right, int bottom, int color) {
        paint2d(() -> drawRect(left, top, right, bottom, color));
    }

    public void text(String text, int x, int y, int color) {
        paint2d(() -> fontRendererObj.drawString(text, x, y, color));
    }

    public boolean item(String registryId, int metadata, int count, int x, int y) {
        Object registered = net.minecraft.item.Item.itemRegistry.getObject(registryId);
        if (!(registered instanceof net.minecraft.item.Item)) return false;
        net.minecraft.item.ItemStack stack =
                new net.minecraft.item.ItemStack(
                        (net.minecraft.item.Item) registered, count, metadata);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            // Give each icon its own depth region, intersected with the current widget/chat clip.
            // A menu's items must not share depth with the conversation's earlier item renderings.
            clip(x, y, x + 16, y + 16);
            try {
                GL11.glDepthMask(true);
                GL11.glClearDepth(1.0);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            } finally {
                unclip();
            }
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
            itemRenderer.renderItemAndEffectIntoGUI(
                    fontRendererObj, mc.getTextureManager(), stack, x, y);
            itemRenderer.renderItemOverlayIntoGUI(
                    fontRendererObj, mc.getTextureManager(), stack, x, y);
            return true;
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    public void clip(int left, int top, int right, int bottom) {
        int[] box = {left, top, right, bottom};
        if (!clips.isEmpty()) {
            int[] parent = clips.peek();
            box[0] = Math.max(box[0], parent[0]);
            box[1] = Math.max(box[1], parent[1]);
            box[2] = Math.min(box[2], parent[2]);
            box[3] = Math.min(box[3], parent[3]);
        }
        clips.push(box);
        applyClip(box);
    }

    private void applyClip(int[] box) {
        double sx = scale * mc.displayWidth / (double) width,
                sy = scale * mc.displayHeight / (double) height;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                (int) (box[0] * sx),
                Math.max(0, mc.displayHeight - (int) Math.ceil(box[3] * sy)),
                Math.max(0, (int) Math.ceil((box[2] - box[0]) * sx)),
                Math.max(0, (int) Math.ceil((box[3] - box[1]) * sy)));
    }

    public void unclip() {
        if (!clips.isEmpty()) clips.pop();
        if (clips.isEmpty()) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        else applyClip(clips.peek());
    }

    public String clipboard() {
        return getClipboardString();
    }

    public void clipboard(String text) {
        setClipboardString(text);
    }

    public void openResource(String location) {
        try {
            URI uri = new URI(location);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !"http".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Only web links can be opened");
            if (!Desktop.isDesktopSupported())
                throw new IllegalStateException("No desktop browser is available");
            Desktop.getDesktop().browse(uri);
        } catch (Exception e) {
            if (ClientHarness.session != null)
                ClientHarness.session.notice = "Could not open resource: " + e.getMessage();
        }
    }

    public void closePanel() {
        mc.displayGuiScreen(null);
    }

    public void ordinaryChat() {
        agent = false;
    }
}
