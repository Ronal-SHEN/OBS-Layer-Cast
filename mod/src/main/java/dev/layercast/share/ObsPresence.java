package dev.layercast.share;

import dev.layercast.LayerCast;
import dev.layercast.config.LayerCastSettings;
import dev.layercast.share.ffm.Native;
import dev.layercast.share.ffm.SharedMemory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Whether the LayerCast OBS plugin is running, for the config screen. Only what the plugin itself reports counts: a
 * source reading this game (its heartbeats in the directory), or else the presence block the plugin keeps fresh while
 * OBS runs (see {@code struct lc_presence}).
 */
public final class ObsPresence {
    public enum Status {
        /** Layer sharing is switched off. */
        DISABLED,
        /** A LayerCast source in OBS reads this game. */
        CONNECTED,
        /** The plugin runs in OBS, but no source reads this game. */
        PLUGIN_RUNNING,
        /** No running plugin found: OBS is not running, or the plugin is missing, outdated or failed to load. */
        NOT_DETECTED
    }

    /** @param shownLayers layers a visible OBS source shows right now (only for {@link Status#CONNECTED}) */
    public record Report(Status status, int shownLayers) {
    }

    private static final long RECHECK_MS = 1000;
    private static long checkedAt;
    private static boolean pluginRunning;

    private ObsPresence() {
    }

    /** Render thread (reads the shared directory). */
    public static Report report() {
        if (!LayerCastSettings.enabled()) {
            return new Report(Status.DISABLED, 0);
        }
        SharingService sharing = LayerCast.sharing();
        if (sharing != null) {
            SharingService.ObsUse use = sharing.obsUse();
            if (use.attached() > 0) {
                return new Report(Status.CONNECTED, use.shown());
            }
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt >= RECHECK_MS) {
            checkedAt = now;
            pluginRunning = readPresence(now);
        }
        return new Report(pluginRunning ? Status.PLUGIN_RUNNING : Status.NOT_DETECTED, 0);
    }

    private static boolean readPresence(long now) {
        try (SharedMemory memory = SharedMemory.openReadOnly(Protocol.presenceName(Native.OS == Native.Os.WINDOWS), Protocol.PRESENCE_SIZE)) {
            if (memory == null) {
                return false;
            }
            MemorySegment seg = memory.segment();
            long heartbeat = seg.get(ValueLayout.JAVA_LONG, Protocol.P_HEARTBEAT);
            return seg.get(ValueLayout.JAVA_INT, Protocol.P_MAGIC) == Protocol.MAGIC
                && heartbeat != 0 && Math.abs(now - heartbeat) < Protocol.PRESENCE_TIMEOUT_MS;
        } catch (Throwable t) {
            LayerCast.LOGGER.debug("Reading the OBS presence block failed", t);
            return false;
        }
    }
}
