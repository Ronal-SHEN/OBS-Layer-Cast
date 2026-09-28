package dev.layercast;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.layercast.compat.RenderCompat;
import dev.layercast.config.LayerCastSettings;
import dev.layercast.layer.Layer;
import dev.layercast.layer.LayerCapture;
import dev.layercast.layer.LayerDebug;
import dev.layercast.layer.LayerRenderer;
import dev.layercast.layer.Layers;
import dev.layercast.layer.NameTagLayer;
import dev.layercast.platform.LoaderPlatform;
import dev.layercast.share.SharingService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point shared by all loaders, and the glue between the frame hooks (mixins) and the capture/sharing code.
 * <p>
 * Every hook is fail-safe: an unexpected exception disables the mod for the rest of the session instead of
 * crashing the game, because a streaming helper must never take the game down with it.
 */
public final class LayerCast {
    public static final String MOD_ID = "layercast";
    public static final String VERSION = /*$ mod_version*/ "1.0.0";
    public static final String MINECRAFT = /*$ minecraft*/ "26.2";
    public static final Logger LOGGER = LoggerFactory.getLogger("LayerCast");

    private static String loader = "unknown";
    private static SharingService sharing;
    private static LayerRenderer renderer;
    private static boolean broken;
    private static boolean recording;
    /** Nanoseconds spent in LayerCast hooks on the render thread (excluding the element tee), for diagnostics. */
    private static long hookNanos;
    private static long hookFrames;
    private static long totalFrames;
    private static long lastFrameStartNs;
    private static final long[] FRAME_INTERVALS = new long[16384];
    private static final long DISCOVERY_INTERVAL_NS = 1_000_000_000L;
    private static final boolean[] DUE = new boolean[Layers.MAX_LAYERS];
    private static final boolean[] SPLIT = new boolean[Layers.MAX_LAYERS];
    private static final java.util.Set<String> FULL_WARNED = new java.util.HashSet<>();
    private static long lastDiscoveryNs;
    private static long extractStartNs;
    /** The game layer is due and nothing is split: it is a copy of the finished frame. */
    private static boolean gameCopy;
    /** The game layer is due and parts are split: it is re-drawn over a copy of the world. */
    private static boolean gameRedraw;
    /** The name tag layer is due (it is not a GUI layer: {@link NameTagLayer} captures it while the world is drawn). */
    private static boolean nameTagsDue;

    private LayerCast() {
    }

    public static void init(LoaderPlatform platform) {
        LoaderPlatform.Holder.set(platform);
        loader = platform.name();
        LayerCapture.setModLayers(LayerCast::modLayer);
        LOGGER.info("OBS Layer Cast {} on Minecraft {} ({})", VERSION, MINECRAFT, loader);
    }

    /** The layer of a mod's HUD; the first time a mod is seen drawing one, the layer is registered (not split). */
    private static @Nullable Layer modLayer(String modId) {
        Layer layer = Layers.byMod(modId);
        if (layer == null) {
            layer = Layers.modLayer(modId, LoaderPlatform.Holder.get().modName(modId));
            if (layer == null) {
                if (FULL_WARNED.add(modId)) {
                    LOGGER.warn("No free layer slot for the HUD of mod '{}'; it stays in the game layer", modId);
                }
                return null;
            }
            LOGGER.info("Found the HUD of mod '{}' ({}); it can be split into layer '{}'", modId, layer.name(), layer.id());
            LayerCastSettings.modLayerRegistered(layer);
        }
        return layer;
    }

    public static String producerDescription(String backendName) {
        return "Minecraft " + MINECRAFT + " (" + loader + ", " + backendName + ")";
    }

    private static void fail(String where, Throwable t) {
        broken = true;
        recording = false;
        LayerCapture.endRecording();
        NameTagLayer.beginFrame(false);
        LOGGER.error("OBS Layer Cast disabled itself after an error in {}", where, t);
        try {
            if (sharing != null) {
                sharing.shutdown();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Before the frame is extracted. */
    public static void onFrameStart() {
        if (broken) {
            return;
        }
        LayerProfiler.frameStart();
        long start = System.nanoTime();
        if (lastFrameStartNs != 0L) {
            FRAME_INTERVALS[(int) (totalFrames % FRAME_INTERVALS.length)] = start - lastFrameStartNs;
        }
        lastFrameStartNs = start;
        hookFrames++;
        totalFrames++;
        try {
            if (sharing == null) {
                sharing = new SharingService();
                renderer = new LayerRenderer();
            }
            sharing.beginFrame(LayerDebug.dumpRequested());
            if (net.minecraft.client.Minecraft.getInstance().level != null) {
                sharing.showPendingNotice();
            }
            renderer.collectIdle();
            if (sharing.anyDue()) {
                LayerDebug.beginFrame();
            }
        } catch (Throwable t) {
            fail("frame start", t);
        } finally {
            hookNanos += System.nanoTime() - start;
            LayerProfiler.end(LayerProfiler.Stage.FRAME_START, start);
        }
    }

    /** Start of the GUI extraction ({@code Gui.extractRenderState}). */
    public static void onGuiExtractStart() {
        extractStartNs = LayerProfiler.start();
        recording = false;
        if (broken || sharing == null) {
            return;
        }
        gameCopy = false;
        gameRedraw = false;
        nameTagsDue = false;
        NameTagLayer.beginFrame(false);
        try {
            boolean anySplit = false;
            boolean modSplit = false;
            for (Layer layer : Layers.all()) {
                int i = layer.index();
                DUE[i] = sharing.isDue(layer);
                SPLIT[i] = LayerCastSettings.split(layer);
                anySplit |= SPLIT[i];
                modSplit |= SPLIT[i] && layer.kind() == Layer.Kind.MOD;
            }
            // With nothing split, the game layer is exactly what the player sees: copy the finished frame. Otherwise it
            // is re-drawn: a copy of the world with every GUI element that is not split out drawn over it.
            int game = Layers.GAME.index();
            gameCopy = DUE[game] && !anySplit;
            DUE[game] = DUE[game] && anySplit;
            gameRedraw = DUE[game];
            // The name tags are drawn with the world, not the GUI. When they are split, the game layer needs them
            // too, to leave them out.
            Layer nameTags = Layers.NAME_TAGS;
            if (nameTags != null) {
                nameTagsDue = DUE[nameTags.index()];
                DUE[nameTags.index()] = false;
                NameTagLayer.beginFrame(SPLIT[nameTags.index()] && (nameTagsDue || gameRedraw));
            }
            boolean anyGui = false;
            for (Layer layer : Layers.all()) {
                anyGui |= DUE[layer.index()];
            }
            // Now and then look at who draws the unclaimed parts of the HUD, even when nothing is shared, so mods
            // show up in the config screen before the player streams.
            long now = System.nanoTime();
            boolean discovery = LayerCastSettings.enabled() && now - lastDiscoveryNs > DISCOVERY_INTERVAL_NS;
            if (anyGui || discovery) {
                if (discovery) {
                    lastDiscoveryNs = now;
                }
                LayerCapture.beginRecording(RenderCompat.mainGuiRenderState(), renderer.prepareRecording(DUE), SPLIT,
                    modSplit, discovery);
                recording = anyGui;
                if (anyGui || gameCopy || nameTagsDue) {
                    LayerProfiler.markShared();
                }
            } else if (gameCopy || nameTagsDue) {
                LayerProfiler.markShared();
            }
        } catch (Throwable t) {
            fail("GUI extraction", t);
        }
    }

    /** Vanilla has extracted its whole GUI; what follows is drawn by other mods (e.g. MaLiLib's overlay event). */
    public static void onVanillaGuiExtracted() {
        LayerCapture.enterUnclaimed();
    }

    /** End of {@code GameRenderer.extract}: nothing more is added to the GUI render state this frame. */
    public static void onGuiExtractEnd() {
        LayerCapture.endRecording();
        if (extractStartNs != 0L) {
            LayerProfiler.end(LayerProfiler.Stage.EXTRACT, extractStartNs);
            extractStartNs = 0L;
        }
    }

    /** The world (and the first-person hand) is about to be drawn. */
    public static void onLevelStart() {
        NameTagLayer.levelStart();
    }

    /** The world and the first-person hand are drawn. */
    public static void onLevelEnd(RenderTarget mainTarget) {
        try {
            NameTagLayer.levelEnd(mainTarget);
        } catch (Throwable t) {
            fail("name tag capture", t);
        }
    }

    /** Whether name tags drawn right now are captured into the name tag layer. */
    public static boolean capturingNameTags() {
        return !broken && NameTagLayer.capturing();
    }

    /**
     * Vanilla draws (a group of) name tags into the main target ({@code draw}); when they are split out, they are
     * also drawn into the name tag layer.
     */
    public static void onNameTags(Runnable draw) {
        if (!capturingNameTags()) {
            draw.run();
            return;
        }
        long start = LayerProfiler.start();
        try {
            NameTagLayer.beforeVanilla(RenderCompat.mainRenderTarget());
        } catch (Throwable t) {
            fail("name tag capture", t);
        }
        LayerProfiler.end(LayerProfiler.Stage.NAME_TAGS, start);
        draw.run();
        if (broken) {
            return;
        }
        start = LayerProfiler.start();
        try {
            NameTagLayer.drawIntoLayer(draw);
        } catch (Throwable t) {
            fail("name tag capture", t);
        }
        LayerProfiler.end(LayerProfiler.Stage.NAME_TAGS, start);
    }

    /**
     * Right before the vanilla GUI is drawn (after other mods' hooks on the start of GUI rendering): the main target
     * holds the finished world, which the re-drawn game layer starts from.
     */
    public static void onBeforeGuiRender(RenderTarget mainTarget) {
        if (broken || !(gameRedraw || NameTagLayer.active())) {
            return;
        }
        long start = LayerProfiler.start();
        int gpu = LayerProfiler.gpuBegin();
        try {
            RenderTarget game = gameRedraw ? renderer.prepareGame(mainTarget) : null;
            NameTagLayer.finish(mainTarget, game);
        } catch (Throwable t) {
            fail("world capture", t);
        }
        LayerProfiler.gpuEnd(LayerProfiler.Stage.WORLD_COPY, gpu);
        LayerProfiler.end(LayerProfiler.Stage.WORLD_COPY, start);
    }

    /** Right after the vanilla GUI was drawn: replay every recorded GUI layer. */
    public static void onAfterGuiRender(RenderTarget mainTarget) {
        if (broken || sharing == null) {
            return;
        }
        long start = System.nanoTime();
        try {
            if (recording && !LayerDebug.active()) {
                for (Layer layer : Layers.all()) {
                    if (layer.kind() != Layer.Kind.GAME && renderer.isRecording(layer) && !LayerCapture.hasContent(layer)
                        && sharing.skipEmpty(layer)) {
                        renderer.discard(layer);
                    }
                }
            }
            Layer nameTags = Layers.NAME_TAGS;
            if (nameTags == null) {
                nameTagsDue = false;
            } else if (nameTagsDue && !NameTagLayer.hasContent() && !LayerDebug.active() && sharing.skipEmpty(nameTags)) {
                nameTagsDue = false;
            }
            if (recording || gameCopy || nameTagsDue) {
                long reserveStart = LayerProfiler.start();
                sharing.reserveSlots(mainTarget.width, mainTarget.height);
                LayerProfiler.end(LayerProfiler.Stage.TRANSPORT, reserveStart);
            }
            if (recording) {
                recording = false;
                for (Layer layer : Layers.all()) {
                    if (renderer.isRecording(layer)) {
                        LayerProfiler.Stage stage = layer.kind() == Layer.Kind.GAME
                            ? LayerProfiler.Stage.GAME_LAYER : LayerProfiler.Stage.SPLIT_LAYERS;
                        long stageStart = LayerProfiler.start();
                        int gpu = LayerProfiler.gpuBegin();
                        GpuTexture texture = renderer.render(layer, mainTarget.width, mainTarget.height);
                        LayerProfiler.gpuEnd(stage, gpu);
                        LayerProfiler.end(stage, stageStart);
                        if (texture != null) {
                            submit(layer, texture, layer.kind() != Layer.Kind.GAME && !LayerCapture.hasContent(layer));
                            LayerDebug.capture(layer, texture);
                        }
                    }
                }
            }
            if (nameTagsDue) {
                nameTagsDue = false;
                GpuTexture texture = NameTagLayer.texture();
                if (texture != null && nameTags != null) {
                    submit(nameTags, texture, !NameTagLayer.hasContent());
                    LayerDebug.capture(nameTags, texture);
                }
            }
            if (gameCopy) {
                gameCopy = false;
                GpuTexture color = mainTarget.getColorTexture();
                submit(Layers.GAME, color, false);
                LayerDebug.capture(Layers.GAME, color);
            }
            if (LayerDebug.active()) {
                LayerDebug.captureFrame(mainTarget.getColorTexture());
            }
            long endStart = LayerProfiler.start();
            int gpu = LayerProfiler.gpuBegin();
            sharing.endFrame();
            LayerProfiler.gpuEnd(LayerProfiler.Stage.FRAME_END, gpu);
            LayerProfiler.end(LayerProfiler.Stage.FRAME_END, endStart);
        } catch (Throwable t) {
            fail("layer rendering", t);
        } finally {
            if (renderer != null) {
                renderer.finishFrame();
            }
            LayerDebug.endFrame();
            hookNanos += System.nanoTime() - start;
        }
    }

    private static void submit(Layer layer, GpuTexture texture, boolean empty) {
        long start = LayerProfiler.start();
        int gpu = LayerProfiler.gpuBegin();
        sharing.submit(layer, texture, empty);
        LayerProfiler.gpuEnd(LayerProfiler.Stage.TRANSPORT, gpu);
        LayerProfiler.end(LayerProfiler.Stage.TRANSPORT, start);
    }

    /** Frame interval percentile (0..1) over the last up to 16384 frames, in milliseconds (diagnostics). */
    public static double frameIntervalPercentileMs(double percentile) {
        int n = (int) Math.min(totalFrames - 1, FRAME_INTERVALS.length);
        if (n <= 0) {
            return 0;
        }
        long[] copy = java.util.Arrays.copyOf(FRAME_INTERVALS, n);
        java.util.Arrays.sort(copy);
        return copy[Math.min(n - 1, (int) (percentile * n))] / 1e6;
    }

    /** The intervals between the last {@code count} frame starts (at most 16384), in nanoseconds (diagnostics). */
    public static long[] recentFrameIntervalsNs(int count) {
        int n = (int) Math.min(Math.min(count, totalFrames - 1), FRAME_INTERVALS.length);
        long[] result = new long[Math.max(0, n)];
        for (int i = 0; i < result.length; i++) {
            result[i] = FRAME_INTERVALS[(int) ((totalFrames - 1 - i) % FRAME_INTERVALS.length)];
        }
        return result;
    }

    /** Number of frames started since launch (diagnostics). */
    public static long frameCount() {
        return totalFrames;
    }

    /** Average render-thread time spent in LayerCast hooks per frame since the last call, in microseconds. */
    public static double takeAverageHookMicros() {
        double average = hookFrames == 0 ? 0 : hookNanos / 1000.0 / hookFrames;
        hookNanos = 0;
        hookFrames = 0;
        return average;
    }

    public static void onRendererClose() {
        try {
            if (sharing != null) {
                sharing.shutdown();
            }
            if (renderer != null) {
                renderer.close();
            }
            NameTagLayer.close();
        } catch (Throwable t) {
            LOGGER.warn("Shutdown failed", t);
        }
        sharing = null;
        renderer = null;
    }

    public static SharingService sharing() {
        return sharing;
    }
}
