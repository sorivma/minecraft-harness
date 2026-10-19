package dev.minecraftharness.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

@Mod(
        modid = "minecraftharness",
        name = "Minecraft Harness",
        version = "0.1.0",
        acceptableRemoteVersions = "*")
public final class HarnessMod {
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (!event.getSide().isClient()) return;
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        ClientHarness.executable =
                config.get(
                                "codex",
                                "executable",
                                "codex",
                                "Codex native executable name or absolute path. Run codex login outside Minecraft first.")
                        .getString();
        config.save();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (!event.getSide().isClient()) return;
        ClientHarness hooks = new ClientHarness();
        MinecraftForge.EVENT_BUS.register(hooks);
        FMLCommonHandler.instance().bus().register(hooks);
    }
}
