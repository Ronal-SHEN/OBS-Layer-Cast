package dev.layercast.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.layercast.LayerCast;
import dev.layercast.config.Configs;
import dev.layercast.layer.Layer;
import dev.layercast.layer.LayerDebug;
import dev.layercast.layer.Layers;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Split matrix: in several scenes (plain HUD, F3, chat screen, inventory, pumpkin overlay, name tags) dumps the
 * layers with nothing split, each part split on its own and everything split. Every dump also contains
 * {@code _frame.png}, the finished game frame of the same frame, so {@code tools/check_layers.py} can verify that the
 * game layer and the split-out layers add up to exactly what the player sees. The name tag scene is dumped once
 * more with the names hidden ({@code nametags/_hidden}): with the name tags split out, the game layer must look like
 * that. Only runs with {@code -Playercast.test.matrix=true}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class LayerCastComponentTest implements FabricClientGameTest {
    /** Entity data of a named mob that shows its name tag and stays where it was summoned. */
    static final String NAMED_ENTITY = "{NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomNameVisible:1b,Tags:[\"layercast\"],CustomName:\"%s\"}";
    static final String NAMED = NAMED_ENTITY.formatted("Name tag layer");

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("layercast.test.matrix")) {
            return;
        }
        Path out = Path.of(System.getProperty("layercast.test.out", "layercast-test")).resolve("matrix");
        disableVsync(context);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            //? if >=26.2 {
            singleplayer.getConnection().waitForChunksRender();
            //?} else
            /*singleplayer.getClientLevel().waitForChunksRender();*/
            TestServerContext server = singleplayer.getServer();
            for (String command : List.of(
                "time set noon", "gamerule spawn_mobs false", "gamemode survival @a",
                "item replace entity @a weapon.mainhand with minecraft:bread 16",
                "item replace entity @a armor.chest with minecraft:diamond_chestplate",
                "give @a minecraft:torch 32", "item replace entity @a weapon.offhand with minecraft:compass",
                "item replace entity @a inventory.4 with minecraft:cobblestone 20",
                "tp @a ~ ~ ~ 0 40",
                "bossbar add layercast:test \"LayerCast Boss\"", "bossbar set layercast:test players @a",
                "scoreboard objectives add lc dummy \"LayerCast\"", "scoreboard objectives setdisplay sidebar lc",
                "scoreboard objectives setdisplay list lc", "scoreboard players set @a lc 42",
                "effect give @a minecraft:speed infinite 1", "effect give @a minecraft:night_vision infinite 0",
                "title @a times 10 100000 10", "title @a subtitle \"subtitle layer\"", "title @a title \"LayerCast\"",
                "say Hello from the chat layer", "say Second chat line")) {
                server.runCommand(command);
            }
            context.runOnClient(mc -> mc.options.showSubtitles().set(true));
            context.waitTicks(40);

            this.phase(context, server, out, "hud", true);
            context.getInput().pressKey(InputConstants.KEY_F3);
            this.phase(context, server, out, "debug", true);
            context.getInput().pressKey(InputConstants.KEY_F3);
            context.setScreen(() -> new ChatScreen("typing in chat", false));
            this.phase(context, server, out, "chat-screen", false);
            context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
            this.phase(context, server, out, "inventory", false);
            context.setScreen(() -> null);
            server.runCommand("item replace entity @a armor.head with minecraft:carved_pumpkin");
            this.phase(context, server, out, "pumpkin", false);
            server.runCommand("item replace entity @a armor.head with minecraft:air");
            this.nameTagScene(context, server);
            this.phase(context, server, out, "nametags", false);
            server.runCommand("execute as @e[tag=layercast] run data merge entity @s {CustomNameVisible:0b}");
            this.split(context, Set.of());
            context.waitTicks(3);
            LayerDebug.requestDump(out.resolve("nametags").resolve("_hidden"));
            context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);
            this.split(context, Set.of());
        }
    }

    /**
     * Named mobs in front of a stone wall: one in the open, one behind a wall (only the see-through part of its name
     * tag shows) and one in a hole under the ground to the right, whose see-through name tag lies partly under the
     * held item. No status effects: their particles fly in front of the camera and differ from frame to frame.
     */
    private void nameTagScene(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("title @a clear");
        server.runCommand("effect clear @a");
        server.runCommand("bossbar set layercast:test visible false");
        server.runCommand("tp @a ~ ~ ~ 0 15");
        for (String command : List.of(
            "fill ~-8 ~ ~10 ~8 ~8 ~10 minecraft:stone",
            "fill ~1 ~ ~4 ~3 ~2 ~4 minecraft:stone",
            "fill ~-3 ~-3 ~3 ~-3 ~-2 ~3 minecraft:air",
            "summon minecraft:villager ~-2 ~ ~6 " + NAMED_ENTITY.formatted("Villager in the open"),
            "summon minecraft:pig ~2 ~ ~6 " + NAMED_ENTITY.formatted("Pig behind a wall"),
            "summon minecraft:chicken ~-3 ~-3 ~3 " + NAMED_ENTITY.formatted("Chicken under the hand"))) {
            server.runCommand("execute at @p run " + command);
        }
        context.waitTicks(20);
    }

    private void phase(ClientGameTestContext context, TestServerContext server, Path out, String name, boolean tab) {
        server.runCommand("advancement revoke @a only minecraft:story/mine_stone");
        server.runCommand("advancement grant @a only minecraft:story/mine_stone");
        if (tab) {
            context.getInput().holdKey(options -> options.keyPlayerList);
        }
        List<Layer> splittable = context.computeOnClient(mc -> Layers.all().stream().filter(Layer::splittable).toList());
        List<Set<Layer>> configs = new ArrayList<>();
        configs.add(Set.of());
        splittable.forEach(layer -> configs.add(Set.of(layer)));
        configs.add(Set.copyOf(splittable));
        for (Set<Layer> config : configs) {
            // Keep the short-lived parts (action bar, subtitles) on screen for every dump.
            server.runCommand("title @a actionbar \"action bar layer\"");
            server.runCommand("playsound minecraft:entity.chicken.ambient master @a");
            this.split(context, config);
            context.waitTicks(3);
            String label = config.isEmpty() ? "none" : config.size() == 1 ? config.iterator().next().id() : "all";
            LayerDebug.requestDump(out.resolve(name).resolve(label));
            context.waitFor(mc -> !LayerDebug.dumpInProgress(), 200);
        }
        if (tab) {
            context.getInput().releaseKey(options -> options.keyPlayerList);
        }
        LayerCast.LOGGER.info("[matrix] {}: {} configurations dumped", name, configs.size());
    }

    /**
     * The tests need no vsync, and with it a sleeping or locked monitor throttles the game to about 1 fps
     * (NVIDIA on Linux), which makes every tick-based wait take minutes.
     */
    static void disableVsync(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(120);
        });
    }

    private void split(ClientGameTestContext context, Set<Layer> layers) {
        context.runOnClient(mc -> {
            for (Layer layer : Layers.all()) {
                ConfigBooleanHotkeyed option = Configs.Split.of(layer);
                if (option != null) {
                    option.setBooleanValue(layers.contains(layer));
                }
            }
        });
    }
}
