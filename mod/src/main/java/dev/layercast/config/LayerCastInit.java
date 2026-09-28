package dev.layercast.config;

import dev.layercast.LayerCast;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

/**
 * MaLiLib initialisation: config handler, config screen and hotkeys. Shared by Fabric (MaLiLib) and
 * NeoForge (MaFgLib, the NeoForge port with the same API).
 */
public final class LayerCastInit implements IInitializationHandler {
    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(LayerCast.MOD_ID, new Configs());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(LayerCast.MOD_ID, "OBS Layer Cast", GuiConfigs::new));
        InputHandler handler = new InputHandler();
        InputEventHandler.getKeybindManager().registerKeybindProvider(handler);
        handler.setCallbacks();
        LayerCastSettings.setProvider(new Configs.Provider());
    }
}
