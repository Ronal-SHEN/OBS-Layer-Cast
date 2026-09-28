package starship.layercast.layer;

import org.jspecify.annotations.Nullable;

/**
 * A capturable layer that can be shared with OBS.
 *
 * @param index slot in the shared directory (0..{@link Layers#MAX_LAYERS}), fixed for the game session
 * @param id    protocol identifier, also used as OBS property value
 * @param name  human readable name shown in OBS
 * @param kind  how the layer content is produced
 * @param modId for {@link Kind#MOD} layers the mod whose HUD it holds, otherwise {@code null}
 */
public record Layer(int index, String id, String name, Kind kind, @Nullable String modId) {
    public enum Kind {
        /** The whole game picture (world and GUI) minus every part split out into a layer of its own. Opaque. */
        GAME,
        /** One vanilla GUI component (hotbar, chat, F3, ...), split out on request. */
        COMPONENT,
        /** The HUD of one other mod, split out on request. */
        MOD,
        /** A part of the world picture (the name tags above entities), split out on request. */
        WORLD
    }

    public boolean opaque() {
        return this.kind == Kind.GAME;
    }

    /** Whether the layer receives GUI elements (the game layer draws them over the world). */
    public boolean isGui() {
        return this.kind != Kind.WORLD;
    }

    /** Whether the player chooses if this layer is split out of the merged GUI. */
    public boolean splittable() {
        return this.kind != Kind.GAME;
    }
}
