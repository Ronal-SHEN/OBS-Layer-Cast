package dev.layercast.platform;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * The few things that differ between mod loaders, implemented in the {@code fabric} and {@code neoforge} packages.
 */
public interface LoaderPlatform {
    String name();

    /**
     * Creates an additional GUI renderer for a layer. It needs no picture-in-picture renderers of its own: layers
     * blit the textures the vanilla renderer produced (see {@code LayerRenderer}). The constructor differs between
     * loaders because NeoForge patches it to take renderer registrations.
     */
    GuiRenderer createGuiRenderer(GuiRenderState state);

    /** Id of the mod whose jar contains {@code type}, or {@code null} if no mod does. May be slow; callers cache. */
    @Nullable String modIdOf(Class<?> type);

    /** Display name of a loaded mod, or its id. */
    String modName(String modId);

    boolean isModLoaded(String modId);

    /** Ids of all loaded mods. */
    Collection<String> modIds();

    /** Mods that {@code modId} requires (not optional integrations). */
    Collection<String> requiredDependencies(String modId);

    final class Holder {
        private static LoaderPlatform instance;

        private Holder() {
        }

        public static void set(LoaderPlatform platform) {
            instance = platform;
        }

        public static LoaderPlatform get() {
            if (instance == null) {
                throw new IllegalStateException("Loader platform not initialised");
            }
            return instance;
        }
    }
}
