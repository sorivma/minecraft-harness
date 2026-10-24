package dev.minecraftharness.forge;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;

/** Enabled only by the local Gradle launch, never by a normally installed mod. */
public final class LocalTestWorld {
    private volatile boolean started;

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (started || event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getMinecraft();
        if (!(client.currentScreen instanceof GuiMainMenu) || client.theWorld != null) return;
        started = true;
        try {
            Path expected =
                    Paths.get(System.getProperty("harness.testWorldDirectory")).toRealPath();
            Path actual = client.mcDataDir.toPath().toRealPath();
            if (!expected.equals(actual)) {
                throw new IOException(
                        "Test world directory does not match Minecraft game directory");
            }
            LocalWorldFiles.resetSaves(actual);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not reset local test worlds", failure);
        }
        WorldSettings settings =
                new WorldSettings(
                        System.nanoTime(),
                        WorldSettings.GameType.CREATIVE,
                        false,
                        false,
                        WorldType.FLAT);
        // Explicit empty feature list: no villages, lakes, decorations or dungeons.
        settings.func_82750_a("2;7,2x3,2;1;").enableCommands();
        client.launchIntegratedServer("harness-test", "Harness Test", settings);
    }

    @SubscribeEvent
    public void worldLoaded(WorldEvent.Load event) {
        if (started && !event.world.isRemote) configure(event.world);
    }

    @SubscribeEvent
    public void worldTick(TickEvent.WorldTickEvent event) {
        if (started && !event.world.isRemote && event.phase == TickEvent.Phase.START) {
            configure(event.world);
            for (Object player : event.world.playerEntities) {
                EntityPlayerMP serverPlayer = (EntityPlayerMP) player;
                if (!serverPlayer.theItemInWorldManager.isCreative()) {
                    serverPlayer.setGameType(WorldSettings.GameType.CREATIVE);
                }
            }
        }
    }

    @SubscribeEvent
    public void entityJoined(EntityJoinWorldEvent event) {
        if (started && !event.world.isRemote && event.entity instanceof EntityLiving) {
            event.setCanceled(true);
        }
    }

    private static void configure(World world) {
        world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        world.setAllowedSpawnTypes(false, false);
        world.setWorldTime(6000L);
        WorldInfo info = world.getWorldInfo();
        info.setRaining(false);
        info.setThundering(false);
        info.setRainTime(Integer.MAX_VALUE);
        info.setThunderTime(Integer.MAX_VALUE);
        world.setRainStrength(0.0F);
        world.setThunderStrength(0.0F);
    }
}
