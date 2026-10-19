package dev.minecraftharness.forge.sound;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({"dev.minecraftharness.forge.sound"})
public final class SoundLoadingPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() {
        return new String[] {SoundLoadingTransformer.class.getName()};
    }

    public String getModContainerClass() {
        return null;
    }

    public String getSetupClass() {
        return null;
    }

    public void injectData(Map<String, Object> data) {}

    public String getAccessTransformerClass() {
        return null;
    }
}
