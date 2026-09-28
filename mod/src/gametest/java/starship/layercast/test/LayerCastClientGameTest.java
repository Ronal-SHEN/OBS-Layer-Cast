package starship.layercast.test;

import starship.layercast.LayerCast;
import starship.layercast.config.GuiConfigs;
import starship.layercast.layer.Layer;
import starship.layercast.layer.LayerDebug;
import starship.layercast.layer.Layers;
import starship.layercast.layer.ModAttribution;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end scenario: builds a world where every HUD component is visible, dumps all layers to PNG and then
 * keeps the scene alive for {@code layercast.test.holdSeconds} so an external OBS instance can be checked.
 * Run with {@code ./gradlew :26.2-fabric:runClientGameTest -Playercast.forceAll=true}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class LayerCastClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("layercast.test.matrix") || Boolean.getBoolean("layercast.test.benchmark")) {
            return; // LayerCastComponentTest / LayerCastBenchmark run instead
        }
        Path out = Path.of(System.getProperty("layercast.test.out", "layercast-test"));
        int holdSeconds = Integer.getInteger("layercast.test.holdSeconds", 0);
        LayerCastComponentTest.disableVsync(context);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            //? if >=26.2 {
            singleplayer.getConnection().waitForChunksRender();
            //?} else
            /*singleplayer.getClientLevel().waitForChunksRender();*/
            TestServerContext server = singleplayer.getServer();
            server.runCommand("time set noon");
            // Survival shows the status bars; food in hand and armor give HUD mods (AppleSkin, armor HUDs) something to show.
            server.runCommand("gamerule spawn_mobs false");
            server.runCommand("gamemode survival @a");
            server.runCommand("item replace entity @a weapon.mainhand with minecraft:bread 16");
            server.runCommand("item replace entity @a armor.chest with minecraft:diamond_chestplate");
            server.runCommand("item replace entity @a armor.head with minecraft:iron_helmet");
            server.runCommand("give @a minecraft:torch 32");
            server.runCommand("give @a minecraft:diamond_sword");
            server.runCommand("item replace entity @a inventory.4 with minecraft:cobblestone 20");
            server.runCommand("item replace entity @a weapon.offhand with minecraft:compass");
            // Look at the ground in reach so block-info HUDs (Jade, WTHIT) have a target.
            server.runCommand("tp @a ~ ~ ~ 0 40");
            // A named villager standing in a pit ahead, so its name tag is in view (name tag layer).
            server.runCommand("execute at @p run fill ~-1 ~-2 ~3 ~-1 ~-1 ~3 minecraft:air");
            server.runCommand("execute at @p run summon minecraft:villager ~-1 ~-2 ~3 " + LayerCastComponentTest.NAMED);
            server.runCommand("bossbar add layercast:test \"LayerCast Boss\"");
            server.runCommand("bossbar set layercast:test players @a");
            server.runCommand("bossbar set layercast:test color purple");
            server.runCommand("scoreboard objectives add lc dummy \"LayerCast\"");
            server.runCommand("scoreboard objectives setdisplay sidebar lc");
            server.runCommand("scoreboard objectives setdisplay list lc");
            server.runCommand("scoreboard players set @a lc 42");
            server.runCommand("scoreboard players set Alex lc 7");
            server.runCommand("effect give @a minecraft:speed infinite 1");
            server.runCommand("effect give @a minecraft:night_vision infinite 0");
            server.runCommand("title @a times 10 100000 10");
            server.runCommand("title @a subtitle \"subtitle layer\"");
            server.runCommand("title @a title \"LayerCast\"");
            server.runCommand("title @a actionbar \"action bar layer\"");
            server.runCommand("say Hello from the chat layer");

            context.getInput().pressKey(InputConstants.KEY_F3);
            context.getInput().holdKey(options -> options.keyPlayerList);
            context.waitTicks(40);
            if (holdSeconds > 0 && !Boolean.getBoolean("layercast.forceAll")) {
                // Layers are rendered on demand: wait until an OBS source asked for (and received) one.
                LayerCast.LOGGER.info("[test] waiting for an OBS consumer");
                context.waitFor(mc -> LayerCast.sharing() != null && LayerCast.sharing().activeLayerCount() > 0, 60 * 20);
                context.waitTicks(20);
            }

            LayerDebug.requestDump(out.resolve("layers"));
            context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);
            context.takeScreenshot("layercast-full-frame");
            LayerCast.LOGGER.info("[test] layer dump written to {}", out.toAbsolutePath());
            if (LayerCast.sharing() != null) {
                LayerCast.sharing().describe().forEach(line -> LayerCast.LOGGER.info("[test] {}", line));
            }

            if (holdSeconds > 0) {
                LayerCast.LOGGER.info("[test] holding the scene for {} s", holdSeconds);
                writeMarker(out.resolve("READY"));
                context.waitTicks(holdSeconds * 20);
                if (Boolean.getBoolean("layercast.test.resize")) {
                    // New window size -> new shared surfaces (new generation); OBS must follow without restarting.
                    context.getInput().resizeWindow(1280, 720);
                    context.waitTicks(40);
                    LayerCast.LOGGER.info("[test] resized, holding for {} s", holdSeconds);
                    writeMarker(out.resolve("RESIZED"));
                    context.waitTicks(holdSeconds * 20);
                }
            }
            context.getInput().releaseKey(options -> options.keyPlayerList);

            // Many HUD mods hide themselves while F3 is open: dump once more without F3 and Tab.
            context.getInput().pressKey(InputConstants.KEY_F3);
            context.waitTicks(20);
            context.runOnClient(mc -> ModAttribution.takeStats());
            context.waitTicks(40);
            context.runOnClient(mc -> LayerCast.LOGGER.info("[test] mod attribution over 2 s: {}", ModAttribution.takeStats()));
            LayerDebug.requestDump(out.resolve("layers-plain"));
            context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);
            context.takeScreenshot("layercast-plain-frame");
            context.runOnClient(mc -> LayerCast.LOGGER.info("[test] layers: {}", Layers.all().stream().map(Layer::id).toList()));
            if (holdSeconds > 0) {
                LayerCast.LOGGER.info("[test] holding the scene without F3 for {} s", holdSeconds);
                writeMarker(out.resolve("PLAIN_READY"));
                context.waitTicks(holdSeconds * 20);
                if (Boolean.getBoolean("layercast.splitAll")) {
                    // Merge everything back while OBS watches: the split layers must disappear from OBS' list.
                    System.setProperty("layercast.splitAll", "false");
                    context.waitTicks(20);
                    LayerDebug.requestDump(out.resolve("layers-merged-back"));
                    context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);
                    writeMarker(out.resolve("MERGED_READY"));
                    context.waitTicks(holdSeconds * 20);
                }
            }

            // The general tab shows whether the OBS plugin runs, with the plugin download button.
            showTab("GENERIC");
            context.setScreen(GuiConfigs::new);
            context.waitTicks(30);
            context.takeScreenshot("layercast-config-gui-general");
            context.setScreen(() -> null);
            context.waitTicks(5);

            // MaLiLib hotkey J+C opens the config screen (on its "split layers" tab, which lists the mods found so far);
            // it is then captured by the "screen" layer.
            showTab("LAYERS");
            context.getInput().holdKey(InputConstants.KEY_J);
            context.getInput().pressKey(InputConstants.KEY_C);
            context.getInput().releaseKey(InputConstants.KEY_J);
            context.waitForScreen(GuiConfigs.class);
            context.waitTicks(10);
            context.takeScreenshot("layercast-config-gui");
            // Scroll to the end of the list, where the mod HUDs found so far are listed.
            context.getInput().setCursorPos(300, 250);
            for (int i = 0; i < 20; i++) {
                context.getInput().scroll(-5);
                context.waitTick();
            }
            context.takeScreenshot("layercast-config-gui-mods");
            LayerDebug.requestDump(out.resolve("layers-config-gui"));
            context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);

            // The player preview in the inventory is a picture-in-picture element: layers reuse vanilla's texture.
            context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
            context.waitTicks(10);
            context.takeScreenshot("layercast-inventory");
            LayerDebug.requestDump(out.resolve("layers-inventory"));
            context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);
            context.setScreen(() -> null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void showTab(String name) {
        try {
            java.lang.reflect.Field tab = GuiConfigs.class.getDeclaredField("tab");
            tab.setAccessible(true);
            tab.set(null, Enum.valueOf((Class) tab.getType(), name));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeMarker(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
