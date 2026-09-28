package dev.layercast.config;

import dev.layercast.layer.Layer;

/**
 * Read-only view of the user configuration used by the capture/sharing code. The values are backed by
 * {@link Configs} (MaLiLib); this indirection keeps the render path independent from the GUI library.
 */
public final class LayerCastSettings {
    private static Provider provider = new Provider() {
    };

    private LayerCastSettings() {
    }

    public static void setProvider(Provider newProvider) {
        provider = newProvider;
    }

    public static boolean enabled() {
        return provider.enabled();
    }

    public static String channel() {
        return provider.channel();
    }

    public static int maxFps() {
        return provider.maxFps();
    }

    public static int slotCount() {
        return provider.slotCount();
    }

    /** Whether a splittable layer is split out of the merged GUI layer. */
    public static boolean split(Layer layer) {
        return layer.splittable() && provider.layerSplit(layer);
    }

    /** Whether the layer is offered to OBS: the world, the merged GUI and every split-out layer. */
    public static boolean available(Layer layer) {
        return !layer.splittable() || provider.layerSplit(layer);
    }

    /** The configured channel is used by another running Minecraft, so this one shares on {@code actual}. */
    public static void channelInUse(String configured, String actual) {
        provider.channelInUse(configured, actual);
    }

    /** A mod's HUD was seen for the first time and got a layer (initially merged). */
    public static void modLayerRegistered(Layer layer) {
        provider.modLayerRegistered(layer);
    }

    public static boolean layerForced(Layer layer) {
        return provider.layerForced(layer);
    }

    public static TransportMode transportMode() {
        return provider.transportMode();
    }

    public enum TransportMode {
        /** GPU sharing when possible, CPU shared memory otherwise. */
        AUTO,
        /** GPU sharing only; layers fail instead of falling back. */
        GPU,
        /** Always copy through CPU shared memory (for troubleshooting). */
        CPU
    }

    public interface Provider {
        default boolean enabled() {
            return true;
        }

        default String channel() {
            return "default";
        }

        default int maxFps() {
            return 60;
        }

        default int slotCount() {
            return 3;
        }

        /** {@code -Dlayercast.splitAll=true} splits every layer (tests). */
        default boolean layerSplit(Layer layer) {
            return Boolean.getBoolean("layercast.splitAll");
        }

        default void modLayerRegistered(Layer layer) {
        }

        default void channelInUse(String configured, String actual) {
        }

        default boolean layerForced(Layer layer) {
            return Boolean.getBoolean("layercast.forceAll");
        }

        default TransportMode transportMode() {
            String mode = System.getProperty("layercast.transport", "auto");
            return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
                case "gpu" -> TransportMode.GPU;
                case "cpu" -> TransportMode.CPU;
                default -> TransportMode.AUTO;
            };
        }
    }
}
