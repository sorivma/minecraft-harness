package dev.minecraftharness.forge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.minecraftharness.core.WorkspaceController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/** A small vanilla-style activity line, visible while the player is in the world. */
public final class AgentHud extends Gui {
    private static final int[] DX = {0, 3, 6, 6, 6, 3, 0, 0};
    private static final int[] DY = {0, 0, 0, 3, 6, 6, 6, 3};

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getMinecraft();
        WorkspaceController session = ClientHarness.session;
        if (session == null
                || mc.theWorld == null
                || mc.currentScreen != null
                || mc.gameSettings.hideGUI) return;
        WorkspaceController.Chat activity = null;
        int active = 0;
        for (WorkspaceController.Chat chat : session.chats()) {
            if (chat.busy() || chat.needsAnswer()) {
                active++;
                if (activity == null
                        || chat.needsAnswer()
                        || chat == session.selected() && !activity.needsAnswer()) activity = chat;
            }
        }
        if (activity == null) return;
        boolean waiting = activity.needsAnswer();
        String text =
                "Codex: "
                        + (waiting
                                ? "Needs answer [T]"
                                : "Working (" + activity.elapsedSeconds() + " s)")
                        + (active > 1 ? " +" + (active - 1) : "");
        ScaledResolution screen = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int x = 4, y = screen.getScaledHeight() - 53;
        drawRect(x, y, x + mc.fontRenderer.getStringWidth(text) + 22, y + 15, 0x90000000);
        if (waiting) mc.fontRenderer.drawStringWithShadow("!", x + 5, y + 4, 0xffcc66);
        else {
            int frame = (int) ((System.nanoTime() / 100_000_000L) % 8);
            for (int i = 0; i < 8; i++) {
                int alpha = 60 + (7 - (frame - i + 8) % 8) * 27;
                drawRect(
                        x + 4 + DX[i],
                        y + 3 + DY[i],
                        x + 6 + DX[i],
                        y + 5 + DY[i],
                        alpha << 24 | 0x8fc9ff);
            }
        }
        mc.fontRenderer.drawStringWithShadow(text, x + 17, y + 4, waiting ? 0xffcc66 : 0xdddddd);
    }
}
