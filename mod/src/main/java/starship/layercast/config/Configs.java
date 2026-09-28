package starship.layercast.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import starship.layercast.LayerCast;
import starship.layercast.layer.Layer;
import starship.layercast.layer.Layers;
import starship.layercast.platform.LoaderPlatform;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MaLiLib configuration: {@code config/layercast.json}, editable in game (default hotkey J+C).
 */
public final class Configs implements IConfigHandler {
    private static final String FILE_NAME = LayerCast.MOD_ID + ".json";
    private static final String GENERIC_KEY = LayerCast.MOD_ID + ".config.generic";
    private static final String LAYERS_KEY = LayerCast.MOD_ID + ".config.layers";
    private static final String HOTKEYS_KEY = LayerCast.MOD_ID + ".config.hotkeys";
    private static final String SPLIT_CATEGORY = "Split";
    private static final String MODS_CATEGORY = "SplitMods";

    public static final class Generic {
        public static final ConfigBooleanHotkeyed ENABLED = new ConfigBooleanHotkeyed("enabled", true, "").apply(GENERIC_KEY);
        public static final ConfigString CHANNEL = new ConfigString("channel", "default").apply(GENERIC_KEY);
        public static final ConfigInteger MAX_FPS = new ConfigInteger("maxFps", 60, 10, 240).apply(GENERIC_KEY);
        public static final ConfigOptionList TRANSPORT = new ConfigOptionList("transport", TransportModeOption.AUTO).apply(GENERIC_KEY);
        public static final ConfigInteger SLOTS = new ConfigInteger("slots", 3, 2, 4).apply(GENERIC_KEY);
        public static final ConfigBoolean RENDER_WITHOUT_OBS = new ConfigBoolean("renderWithoutObs", false).apply(GENERIC_KEY);

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
            ENABLED, CHANNEL, MAX_FPS, TRANSPORT, SLOTS, RENDER_WITHOUT_OBS);

        private Generic() {
        }
    }

    /**
     * Which parts of the GUI are split out of the {@code game} layer into layers of their own: the vanilla
     * components (fixed) and the HUD of every mod seen drawing one (added at runtime, remembered in the file).
     * Everything starts merged; each toggle has an optional hotkey to switch it live.
     */
    public static final class Split {
        public static final ImmutableList<ConfigBooleanHotkeyed> COMPONENTS;
        private static final Map<Layer, ConfigBooleanHotkeyed> BY_LAYER = new HashMap<>();
        private static final Map<String, ConfigBooleanHotkeyed> MODS = new LinkedHashMap<>();
        /** Entries of mods that are not installed any more, written back unchanged. */
        private static JsonObject uninstalledMods = new JsonObject();

        static {
            ImmutableList.Builder<ConfigBooleanHotkeyed> components = ImmutableList.builder();
            for (Layer layer : Layers.all()) {
                if (layer.kind() == Layer.Kind.COMPONENT || layer.kind() == Layer.Kind.WORLD) {
                    ConfigBooleanHotkeyed option = new ConfigBooleanHotkeyed(layer.id(), false, "").apply(LAYERS_KEY);
                    components.add(option);
                    BY_LAYER.put(layer, option);
                }
            }
            COMPONENTS = components.build();
        }

        private Split() {
        }

        public static @Nullable ConfigBooleanHotkeyed of(Layer layer) {
            if (layer.kind() == Layer.Kind.MOD) {
                return MODS.get(layer.modId());
            }
            return BY_LAYER.get(layer);
        }

        /** The toggle of a mod's layer, created (merged) if the mod is new. */
        static ConfigBooleanHotkeyed mod(Layer layer) {
            String modId = java.util.Objects.requireNonNull(layer.modId());
            return MODS.computeIfAbsent(modId, id -> {
                ConfigBooleanHotkeyed option = new ConfigBooleanHotkeyed(id, false, "",
                    "layercast.config.mod_layer.comment", layer.name(), layer.name());
                option.getKeybind().setCallback(new KeyCallbackToggleBooleanConfigWithMessage(option));
                return option;
            });
        }

        public static List<IConfigBase> options() {
            List<IConfigBase> list = new ArrayList<>(COMPONENTS);
            list.addAll(MODS.values());
            return list;
        }

        static List<ConfigBooleanHotkeyed> all() {
            List<ConfigBooleanHotkeyed> list = new ArrayList<>(COMPONENTS);
            list.addAll(MODS.values());
            return list;
        }

        /** Creates the layers and toggles of the mods remembered in the file (installed ones only). */
        private static void readMods(JsonObject root) {
            uninstalledMods = new JsonObject();
            if (!root.has(MODS_CATEGORY) || !root.get(MODS_CATEGORY).isJsonObject()) {
                return;
            }
            JsonObject mods = root.getAsJsonObject(MODS_CATEGORY);
            LoaderPlatform platform = LoaderPlatform.Holder.get();
            for (Map.Entry<String, JsonElement> entry : mods.entrySet()) {
                String modId = entry.getKey();
                Layer layer = platform.isModLoaded(modId) ? Layers.modLayer(modId, platform.modName(modId)) : null;
                if (layer != null) {
                    mod(layer);
                } else {
                    uninstalledMods.add(modId, entry.getValue());
                }
            }
            ConfigUtils.readConfigBase(root, MODS_CATEGORY, ImmutableList.copyOf(MODS.values()));
        }

        private static void writeMods(JsonObject root) {
            ConfigUtils.writeConfigBase(root, MODS_CATEGORY, ImmutableList.copyOf(MODS.values()));
            JsonObject mods = root.getAsJsonObject(MODS_CATEGORY);
            if (mods == null) {
                mods = new JsonObject();
                root.add(MODS_CATEGORY, mods);
            }
            for (Map.Entry<String, JsonElement> entry : uninstalledMods.entrySet()) {
                if (!mods.has(entry.getKey())) {
                    mods.add(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public static final class Hotkeys {
        public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey("openConfigGui", "J,C").apply(HOTKEYS_KEY);
        public static final ConfigHotkey SHOW_STATUS = new ConfigHotkey("showStatus", "J,S").apply(HOTKEYS_KEY);
        public static final ConfigHotkey DUMP_LAYERS = new ConfigHotkey("dumpLayers", "").apply(HOTKEYS_KEY);

        public static final ImmutableList<ConfigHotkey> HOTKEYS = ImmutableList.of(OPEN_CONFIG_GUI, SHOW_STATUS, DUMP_LAYERS);

        private Hotkeys() {
        }
    }

    /** Every hotkey owned by the mod, including the toggle hotkeys of boolean options. */
    public static List<IHotkey> allHotkeys() {
        List<IHotkey> list = new ArrayList<>(Hotkeys.HOTKEYS);
        list.add(Generic.ENABLED);
        list.addAll(Split.all());
        return list;
    }

    public static void loadFromFile() {
        Path file = FileUtils.getConfigDirectory().resolve(FILE_NAME);
        if (Files.isReadable(file)) {
            JsonElement element = JsonUtils.parseJsonFile(file);
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
                ConfigUtils.readConfigBase(root, SPLIT_CATEGORY, Split.COMPONENTS);
                Split.readMods(root);
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEYS);
            }
        }
    }

    public static void saveToFile() {
        Path dir = FileUtils.getConfigDirectory();
        if (!FileUtils.createDirectoriesIfMissing(dir)) {
            LayerCast.LOGGER.error("Config directory {} does not exist", dir);
            return;
        }
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
        ConfigUtils.writeConfigBase(root, SPLIT_CATEGORY, Split.COMPONENTS);
        Split.writeMods(root);
        ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEYS);
        JsonUtils.writeJsonToFile(root, dir.resolve(FILE_NAME));
    }

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }

    /** Exposes the MaLiLib values to the render/sharing code. */
    public static final class Provider implements LayerCastSettings.Provider {
        @Override
        public boolean enabled() {
            return Generic.ENABLED.getBooleanValue();
        }

        @Override
        public String channel() {
            return Generic.CHANNEL.getStringValue();
        }

        @Override
        public int maxFps() {
            return Generic.MAX_FPS.getIntegerValue();
        }

        @Override
        public int slotCount() {
            return Generic.SLOTS.getIntegerValue();
        }

        @Override
        public boolean layerSplit(Layer layer) {
            if (LayerCastSettings.Provider.super.layerSplit(layer)) {
                return true;
            }
            ConfigBooleanHotkeyed option = Split.of(layer);
            return option != null && option.getBooleanValue();
        }

        @Override
        public void channelInUse(String configured, String actual) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, 8000, "layercast.message.channel_in_use", configured, actual);
        }

        @Override
        public void modLayerRegistered(Layer layer) {
            Split.mod(layer);
            // Make the new toggle's hotkey work and remember the mod for the next session.
            InputEventHandler.getKeybindManager().updateUsedKeys();
            saveToFile();
        }

        @Override
        public boolean layerForced(Layer layer) {
            return Generic.RENDER_WITHOUT_OBS.getBooleanValue() || LayerCastSettings.Provider.super.layerForced(layer);
        }

        @Override
        public LayerCastSettings.TransportMode transportMode() {
            String override = System.getProperty("layercast.transport");
            if (override != null) {
                return LayerCastSettings.Provider.super.transportMode();
            }
            return ((TransportModeOption) Generic.TRANSPORT.getOptionListValue()).mode();
        }
    }
}
