package dev.layercast.layer;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All layers of the session: the built-in ones plus one per mod HUD, registered when the mod is first seen
 * drawing (or read from the config). Indices never change during a session. Render thread only, except the
 * built-in constants.
 */
public final class Layers {
    public static final int MAX_LAYERS = 32;
    /** Mod layer ids are {@code mod.<mod id>}; the protocol allows 31 bytes. */
    public static final String MOD_PREFIX = "mod.";
    private static final int MAX_ID_LENGTH = 31;

    private static final List<Layer> ALL = new ArrayList<>();
    private static final List<Layer> VIEW = Collections.unmodifiableList(ALL);
    private static final Map<String, Layer> BY_ID = new HashMap<>();
    private static final Map<String, Layer> BY_MOD = new HashMap<>();

    public static final Layer GAME = register("game", "Game (everything not split out)", Layer.Kind.GAME, null);
    public static final Layer HOTBAR = component("hotbar", "Hotbar & status bars");
    public static final Layer CROSSHAIR = component("crosshair", "Crosshair");
    public static final Layer EFFECTS = component("effects", "Status effects");
    public static final Layer BOSSBAR = component("bossbar", "Boss bars");
    public static final Layer SCOREBOARD = component("scoreboard", "Scoreboard sidebar");
    public static final Layer ACTIONBAR = component("actionbar", "Action bar message");
    public static final Layer TITLE = component("title", "Title & subtitle");
    public static final Layer CHAT = component("chat", "Chat");
    public static final Layer TABLIST = component("tablist", "Player list (Tab)");
    public static final Layer SUBTITLES = component("subtitles", "Sound subtitles");
    public static final Layer DEBUG = component("debug", "Debug screen (F3)");
    public static final Layer SCREEN = component("screen", "Open screens / menus");
    public static final Layer TOASTS = component("toasts", "Toasts");
    public static final Layer CAMERA = component("camera", "Camera overlays (vignette, pumpkin, portal)");
    /**
     * Drawn by the level renderer, not the GUI: captured by {@link NameTagLayer}. Not available on 26.3, which draws
     * name tags together with all other text in the world.
     */
    //? if <26.3 {
    public static final @Nullable Layer NAME_TAGS = register("nametags", "Name tags", Layer.Kind.WORLD, null);
    //?} else
    /*public static final @Nullable Layer NAME_TAGS = null;*/
    /** Number of built-in layers; everything after them is a mod layer. */
    public static final int BUILT_IN = ALL.size();

    private Layers() {
    }

    private static Layer component(String id, String name) {
        return register(id, name, Layer.Kind.COMPONENT, null);
    }

    private static Layer register(String id, String name, Layer.Kind kind, @Nullable String modId) {
        Layer layer = new Layer(ALL.size(), id, name, kind, modId);
        ALL.add(layer);
        BY_ID.put(id, layer);
        if (modId != null) {
            BY_MOD.put(modId, layer);
        }
        return layer;
    }

    /**
     * The layer for a mod's HUD, registering it if needed.
     *
     * @return {@code null} when all directory slots are taken (the mod's HUD then stays in the merged layer)
     */
    public static @Nullable Layer modLayer(String modId, String displayName) {
        Layer layer = BY_MOD.get(modId);
        if (layer != null) {
            return layer;
        }
        if (ALL.size() >= MAX_LAYERS) {
            return null;
        }
        String id = MOD_PREFIX + modId;
        if (id.length() > MAX_ID_LENGTH) {
            id = id.substring(0, MAX_ID_LENGTH - 4) + Integer.toHexString(modId.hashCode() & 0xFFF);
        }
        while (BY_ID.containsKey(id)) {
            id = id.substring(0, Math.min(id.length(), MAX_ID_LENGTH - 1)) + "_";
        }
        return register(id, displayName, Layer.Kind.MOD, modId);
    }

    public static @Nullable Layer byMod(String modId) {
        return BY_MOD.get(modId);
    }

    public static List<Layer> all() {
        return VIEW;
    }

    public static @Nullable Layer byId(String id) {
        return BY_ID.get(id);
    }

    public static @Nullable Layer byIndex(int index) {
        return index >= 0 && index < ALL.size() ? ALL.get(index) : null;
    }

    public static int count() {
        return ALL.size();
    }
}
