package dev.minecraftharness.forge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import dev.minecraftharness.chat.ChatPanel;
import dev.minecraftharness.chat.widget.WidgetRegistry;
import dev.minecraftharness.codex.CodexBackend;
import dev.minecraftharness.core.*;
import java.io.*;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.client.event.GuiOpenEvent;

public final class ClientHarness {
    public static String executable = "codex";
    public static WorkspaceController session;
    public static ChatPanel panel;
    public static String unavailable = "Open a local world first";
    private File worldRoot;
    private IntegratedServer server;
    private boolean attempted;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        IntegratedServer current = mc.isSingleplayer() ? mc.getIntegratedServer() : null;
        // Identity also changes when the same save is closed and reopened.
        if (current != server) {
            if (session != null) session.close();
            session = null;
            panel = null;
            worldRoot = null;
            server = current;
            attempted = false;
            unavailable =
                    current == null ? "Codex needs a local world save" : "Waiting for world save";
        }
        if (current != null && !attempted && current.worldServerForDimension(0) != null) {
            File root = current.worldServerForDimension(0).getSaveHandler().getWorldDirectory();
            if (root != null) {
                attempted = true;
                try {
                    worldRoot = root.getCanonicalFile();
                    WidgetRegistry widgets = HarnessWidgets.snapshot();
                    session =
                            new WorkspaceController(
                                    WorldWorkspace.open(worldRoot),
                                    Arrays.asList(
                                            new CodexBackend(executable, widgets.instructions())));
                    panel = new ChatPanel(session, widgets);
                    unavailable = "";
                } catch (IOException e) {
                    unavailable = "Workspace error: " + e.getMessage();
                }
            }
        }
        if (session != null) session.tick(128);
    }

    @SubscribeEvent
    public void openChat(GuiOpenEvent event) {
        if (event.gui != null && event.gui.getClass() == GuiChat.class) {
            String initial =
                    ReflectionHelper.getPrivateValue(
                            GuiChat.class,
                            (GuiChat) event.gui,
                            "defaultInputFieldText",
                            "field_146409_v");
            event.gui = new CodexChatScreen(initial);
        }
    }
}
