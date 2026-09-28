package dev.layercast.config;

import dev.layercast.LayerCast;
import dev.layercast.layer.LayerDebug;
import dev.layercast.share.SharingService;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Registers the mod's hotkeys with MaLiLib and implements their actions.
 */
public final class InputHandler implements IKeybindProvider, IHotkeyCallback {
    @Override
    public void addKeysToMap(IKeybindManager manager) {
        Configs.allHotkeys().forEach(hotkey -> manager.addKeybindToMap(hotkey.getKeybind()));
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(LayerCast.MOD_ID, "layercast.hotkeys.category.generic", Configs.allHotkeys());
    }

    public void setCallbacks() {
        Configs.Hotkeys.HOTKEYS.forEach(hotkey -> hotkey.getKeybind().setCallback(this));
        Configs.Generic.ENABLED.getKeybind().setCallback(new KeyCallbackToggleBooleanConfigWithMessage(Configs.Generic.ENABLED));
        for (ConfigBooleanHotkeyed toggle : Configs.Split.all()) {
            toggle.getKeybind().setCallback(new KeyCallbackToggleBooleanConfigWithMessage(toggle));
        }
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        if (key == Configs.Hotkeys.OPEN_CONFIG_GUI.getKeybind()) {
            GuiBase.openGui(new GuiConfigs());
            return true;
        }
        if (key == Configs.Hotkeys.SHOW_STATUS.getKeybind()) {
            SharingService sharing = LayerCast.sharing();
            int active = sharing == null ? 0 : sharing.activeLayerCount();
            String channel = sharing != null && sharing.channelName() != null ? sharing.channelName() : Configs.Generic.CHANNEL.getStringValue();
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "layercast.message.status",
                Configs.Generic.ENABLED.getBooleanValue() ? "ON" : "OFF", active, channel);
            if (sharing != null) {
                sharing.describe().forEach(line -> LayerCast.LOGGER.info("[status] {}", line));
            }
            return true;
        }
        if (key == Configs.Hotkeys.DUMP_LAYERS.getKeybind()) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("layercast-dumps")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            LayerDebug.requestDump(dir);
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "layercast.message.dump", dir.toString());
            return true;
        }
        return false;
    }
}
