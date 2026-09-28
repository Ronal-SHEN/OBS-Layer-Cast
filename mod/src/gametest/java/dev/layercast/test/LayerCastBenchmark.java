package dev.layercast.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.layercast.LayerCast;
import dev.layercast.LayerProfiler;
import dev.layercast.config.LayerCastSettings;
import dev.layercast.layer.Layer;
import dev.layercast.layer.Layers;
import dev.layercast.layer.ModAttribution;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Measures the cost of layer sharing: frame rate, frame-time percentiles and hitches (frames that take much longer
 * than the median) for LayerCast disabled, enabled but unused, only the game layer, a typical streaming setup and
 * everything split. Layers are forced on (no OBS needed). Only runs with {@code -Playercast.test.benchmark=true};
 * {@code -Playercast.test.f3=true} keeps F3 open, {@code layercast.test.width/height} set the window size.
 */
@SuppressWarnings("UnstableApiUsage")
public final class LayerCastBenchmark implements FabricClientGameTest {
    private static final int SECONDS = Integer.getInteger("layercast.test.seconds", 10);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("layercast.test.benchmark")) {
            return;
        }
        int width = Integer.getInteger("layercast.test.width", 1920);
        int height = Integer.getInteger("layercast.test.height", 1080);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            //? if >=26.2 {
            singleplayer.getConnection().waitForChunksRender();
            //?} else
            /*singleplayer.getClientLevel().waitForChunksRender();*/
            TestServerContext server = singleplayer.getServer();
            for (String command : List.of("gamemode survival @a", "gamerule spawn_mobs false", "time set noon",
                "bossbar add layercast:bench \"Benchmark\"", "bossbar set layercast:bench players @a",
                "scoreboard objectives add bench dummy \"Bench\"", "scoreboard objectives setdisplay sidebar bench",
                "scoreboard players set @a bench 1", "effect give @a minecraft:speed infinite 1",
                "say benchmark chat line", "give @a minecraft:torch 32")) {
                server.runCommand(command);
            }
            context.getInput().resizeWindow(width, height);
            context.runOnClient(mc -> {
                mc.options.enableVsync().set(false);
                // 260 = unlimited; a lower limit shows the cost at a realistic frame rate, where every layer is
                // captured in the same frame instead of being spread over several fast frames.
                mc.options.framerateLimit().set(Integer.getInteger("layercast.test.fpsLimit", 260));
                // No input arrives during the benchmark: keep Minecraft from dropping to 30 fps after a minute "AFK".
                mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            });
            if (Boolean.getBoolean("layercast.test.f3")) {
                context.getInput().pressKey(InputConstants.KEY_F3);
            }
            context.waitTicks(100); // let mods show their HUDs, so their layers exist

            Supplier<Set<Layer>> all = () -> new HashSet<>(Layers.all());
            Supplier<Set<Layer>> typical = () -> {
                Set<Layer> set = new HashSet<>(Set.of(Layers.GAME, Layers.CHAT, Layers.HOTBAR, Layers.DEBUG));
                Layer minimap = Layers.byMod("xaerominimap");
                if (minimap != null) {
                    set.add(minimap);
                }
                return set;
            };
            this.measure(context, "LayerCast disabled", false, Set::of);
            this.measure(context, "enabled, no layer shared", true, Set::of);
            this.measure(context, "game only (nothing split)", true, () -> Set.of(Layers.GAME));
            this.measure(context, "game + chat, hotbar, debug, minimap split", true, typical);
            this.measure(context, "everything split", true, all);
            this.measure(context, "LayerCast disabled (again)", false, Set::of);
        }
    }

    private void measure(ClientGameTestContext context, String label, boolean enabled, Supplier<Set<Layer>> layersSupplier) {
        Set<Layer> layers = context.computeOnClient(mc -> layersSupplier.get());
        LayerCastSettings.setProvider(new LayerCastSettings.Provider() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public boolean layerSplit(Layer layer) {
                return layers.contains(layer);
            }

            @Override
            public boolean layerForced(Layer layer) {
                return layers.contains(layer);
            }

            @Override
            public int maxFps() {
                return 60;
            }
        });
        context.waitTicks(40); // let allocations settle
        context.runOnClient(mc -> {
            LayerCast.takeAverageHookMicros();
            ModAttribution.takeStats();
            LayerProfiler.takeReport();
        });
        long frames0 = context.computeOnClient(mc -> LayerCast.frameCount());
        long t0 = System.nanoTime();
        context.waitTicks(SECONDS * 20);
        long t1 = System.nanoTime();
        long frames1 = context.computeOnClient(mc -> LayerCast.frameCount());
        double hook = context.computeOnClient(mc -> LayerCast.takeAverageHookMicros());
        String attribution = context.computeOnClient(mc -> ModAttribution.takeStats());
        String profile = context.computeOnClient(mc -> LayerProfiler.takeReport());
        long[] intervals = context.computeOnClient(mc -> LayerCast.recentFrameIntervalsNs((int) (frames1 - frames0)));
        Arrays.sort(intervals);
        double fps = (frames1 - frames0) / ((t1 - t0) / 1e9);
        double p50 = percentile(intervals, 0.5);
        double p99 = percentile(intervals, 0.99);
        double max = percentile(intervals, 1.0);
        long hitches = Arrays.stream(intervals).filter(ns -> ns / 1e6 > Math.max(2 * p50, p50 + 4)).count();
        String size = context.computeOnClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        LayerCast.LOGGER.info("[bench] {} @ {}: {} fps | frame p50 {} ms, p99 {} ms, max {} ms | hitches {} of {} frames | hooks {} us/frame | layers {} | {}",
            label, size, String.format("%.1f", fps), String.format("%.2f", p50), String.format("%.2f", p99),
            String.format("%.2f", max), hitches, intervals.length, String.format("%.1f", hook), layers.size(), attribution);
        if (LayerProfiler.ENABLED) {
            LayerCast.LOGGER.info("[bench] {} profile: {}", label, profile);
            context.runOnClient(mc -> {
                if (LayerCast.sharing() != null) {
                    LayerCast.sharing().describe().forEach(line -> LayerCast.LOGGER.info("[bench]   {}", line));
                }
            });
        }
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.min(sorted.length - 1, (int) (p * sorted.length))] / 1e6;
    }
}
