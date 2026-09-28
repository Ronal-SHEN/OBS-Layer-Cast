package dev.layercast;

import dev.layercast.compat.RenderCompat;
import dev.layercast.share.ffm.Native;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.util.ArrayDeque;

/**
 * Optional timing breakdown of the work LayerCast adds to a frame ({@code -Dlayercast.profile=true}): CPU time per
 * stage and, on OpenGL, GPU time from timestamp queries, plus the frame interval of frames that shared layers versus
 * frames that did not. While playing, the breakdown is logged every {@code layercast.profile.interval} seconds
 * (default 10). Diagnostics only; every method returns immediately when profiling is off. Render thread only.
 */
public final class LayerProfiler {
    public static final boolean ENABLED = Boolean.getBoolean("layercast.profile");
    /** Interval of the periodic log line; the benchmark reads the numbers itself. */
    private static final long LOG_INTERVAL_NS = Boolean.getBoolean("layercast.test.benchmark") ? 0L
        : Long.getLong("layercast.profile.interval", 10L) * 1_000_000_000L;
    private static long lastLogNs;

    public enum Stage {
        /** Deciding what to capture, directory heartbeat, publishing finished frames. */
        FRAME_START("frame start"),
        /** The whole GUI extraction (vanilla and mods), which includes routing elements to layers. */
        EXTRACT("GUI extraction"),
        /** Copying the world before the name tags and drawing them a second time into the name tag layer. */
        NAME_TAGS("name tags"),
        /** Copying the finished world for the game layer (and taking the name tags out of it). */
        WORLD_COPY("world copy"),
        /** Drawing the unsplit GUI over the world copy. */
        GAME_LAYER("game layer"),
        /** Drawing the split-out layers. */
        SPLIT_LAYERS("split layers"),
        /** Copying finished layers into the shared surfaces. */
        TRANSPORT("transport"),
        FRAME_END("frame end");

        final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final int STAGES = Stage.values().length;
    private static final long[] CPU_SHARED = new long[STAGES];
    private static final long[] CPU_OTHER = new long[STAGES];
    private static final long[] GPU = new long[STAGES];
    private static final ArrayDeque<int[]> PENDING = new ArrayDeque<>();
    private static final java.util.Map<String, long[]> DETAILS = new java.util.LinkedHashMap<>();
    private static final ArrayDeque<Integer> FREE_QUERIES = new ArrayDeque<>();
    private static int gpuState; // 0 unknown, 1 available, -1 unavailable
    private static boolean shared;
    private static long sharedFrames;
    private static long otherFrames;
    private static long sharedIntervalNs;
    private static long otherIntervalNs;
    private static long lastFrameNs;
    private static boolean lastFrameShared;

    private LayerProfiler() {
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(Stage stage, long start) {
        if (ENABLED) {
            (shared ? CPU_SHARED : CPU_OTHER)[stage.ordinal()] += System.nanoTime() - start;
        }
    }

    /** Accumulates a named detail timing (count and total) for the report. */
    public static void detail(String name, long start) {
        if (ENABLED) {
            long[] entry = DETAILS.computeIfAbsent(name, k -> new long[2]);
            entry[0]++;
            entry[1] += System.nanoTime() - start;
        }
    }

    /** Start of a frame: accounts the previous frame's interval and collects finished GPU timings. */
    public static void frameStart() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            if (lastFrameShared) {
                sharedFrames++;
                sharedIntervalNs += now - lastFrameNs;
            } else {
                otherFrames++;
                otherIntervalNs += now - lastFrameNs;
            }
        }
        lastFrameNs = now;
        lastFrameShared = false;
        shared = false;
        collectGpu();
        if (LOG_INTERVAL_NS > 0L && now - lastLogNs > LOG_INTERVAL_NS) {
            if (lastLogNs != 0L) {
                LayerCast.LOGGER.info("[profile] {} | attribution: {}", takeReport(), dev.layercast.layer.ModAttribution.takeStats());
            }
            lastLogNs = now;
        }
    }

    /** This frame records or shares layers (its CPU time is accounted separately). */
    public static void markShared() {
        if (ENABLED) {
            shared = true;
            lastFrameShared = true;
        }
    }

    /** Begins a GPU interval; returns a token for {@link #gpuEnd}, or -1. */
    public static int gpuBegin() {
        if (!ENABLED || !gpuAvailable()) {
            return -1;
        }
        int query = FREE_QUERIES.isEmpty() ? GL15C.glGenQueries() : FREE_QUERIES.pop();
        GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP);
        return query;
    }

    public static void gpuEnd(Stage stage, int begin) {
        if (begin < 0) {
            return;
        }
        int query = FREE_QUERIES.isEmpty() ? GL15C.glGenQueries() : FREE_QUERIES.pop();
        GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP);
        PENDING.add(new int[] {stage.ordinal(), begin, query});
    }

    private static boolean gpuAvailable() {
        if (gpuState == 0) {
            // Apple's OpenGL answers timestamp queries with zeros (and they are slow), so there is no GPU timing on macOS.
            boolean gl = RenderCompat.backendName().toLowerCase(java.util.Locale.ROOT).contains("opengl")
                && Native.OS != Native.Os.MACOS;
            try {
                gpuState = gl && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query) ? 1 : -1;
            } catch (Throwable t) {
                gpuState = -1;
            }
        }
        return gpuState > 0;
    }

    private static void collectGpu() {
        while (!PENDING.isEmpty()) {
            int[] interval = PENDING.peekFirst();
            if (GL15C.glGetQueryObjecti(interval[2], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
            PENDING.pollFirst();
            long begin = GL33C.glGetQueryObjecti64(interval[1], GL15C.GL_QUERY_RESULT);
            long end = GL33C.glGetQueryObjecti64(interval[2], GL15C.GL_QUERY_RESULT);
            GPU[interval[0]] += end - begin;
            FREE_QUERIES.push(interval[1]);
            FREE_QUERIES.push(interval[2]);
        }
    }

    /** The breakdown since the last call, then resets it. */
    public static String takeReport() {
        if (!ENABLED) {
            return "profiling off";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("%d sharing frames (interval %.3f ms) vs %d other frames (%.3f ms) | per sharing frame, CPU us:",
            sharedFrames, sharedFrames == 0 ? 0 : sharedIntervalNs / 1e6 / sharedFrames,
            otherFrames, otherFrames == 0 ? 0 : otherIntervalNs / 1e6 / otherFrames));
        for (Stage stage : Stage.values()) {
            if (stage != Stage.FRAME_START) {
                out.append(String.format(" %s %.1f", stage.label, sharedFrames == 0 ? 0 : CPU_SHARED[stage.ordinal()] / 1e3 / sharedFrames));
            }
        }
        out.append(" | GPU us:");
        for (Stage stage : Stage.values()) {
            if (GPU[stage.ordinal()] != 0) {
                out.append(String.format(" %s %.1f", stage.label, sharedFrames == 0 ? 0 : GPU[stage.ordinal()] / 1e3 / sharedFrames));
            }
        }
        out.append(String.format(" | frame start (every frame) %.1f us", sharedFrames + otherFrames == 0 ? 0
            : CPU_OTHER[Stage.FRAME_START.ordinal()] / 1e3 / (sharedFrames + otherFrames)));
        out.append(" | per other frame, CPU us:");
        for (Stage stage : Stage.values()) {
            if (stage != Stage.FRAME_START && CPU_OTHER[stage.ordinal()] != 0) {
                out.append(String.format(" %s %.1f", stage.label, otherFrames == 0 ? 0 : CPU_OTHER[stage.ordinal()] / 1e3 / otherFrames));
            }
        }
        DETAILS.forEach((name, entry) -> out.append(String.format(" | %s: %d x %.1f us", name, entry[0], entry[1] / 1e3 / Math.max(1, entry[0]))));
        DETAILS.clear();
        java.util.Arrays.fill(CPU_SHARED, 0);
        java.util.Arrays.fill(CPU_OTHER, 0);
        java.util.Arrays.fill(GPU, 0);
        sharedFrames = 0;
        otherFrames = 0;
        sharedIntervalNs = 0;
        otherIntervalNs = 0;
        return out.toString();
    }
}
