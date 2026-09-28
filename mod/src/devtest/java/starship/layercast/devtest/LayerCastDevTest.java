package starship.layercast.devtest;

import starship.layercast.LayerCast;
import starship.layercast.compat.RenderCompat;
import starship.layercast.config.GuiConfigs;
import starship.layercast.layer.Layer;
import starship.layercast.layer.LayerDebug;
import starship.layercast.layer.Layers;
import starship.layercast.layer.ModAttribution;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * NeoForge has no client game-test framework, so this dev-only mod replays the Fabric game test scenario with a
 * small tick-driven state machine. Enabled with {@code -Dlayercast.test.autorun=true}.
 */
@Mod(value = "layercast_devtest", dist = Dist.CLIENT)
public final class LayerCastDevTest {
    private static final List<String> COMMANDS = List.of(
        "time set noon",
        // Survival shows the status bars; food in hand and armor give HUD mods (AppleSkin, armor HUDs) something to show.
        "gamerule spawn_mobs false",
        "gamemode survival @a",
        "item replace entity @a weapon.mainhand with minecraft:bread 16",
        "item replace entity @a armor.chest with minecraft:diamond_chestplate",
        "item replace entity @a armor.head with minecraft:iron_helmet",
        "give @a minecraft:torch 32",
        "give @a minecraft:diamond_sword",
        "item replace entity @a inventory.4 with minecraft:cobblestone 20",
        "item replace entity @a weapon.offhand with minecraft:compass",
        // Look at a block in reach so block-info HUDs (Jade, WTHIT) have a target (the NeoForge test world is random).
        "tp @a ~ ~ ~ 0 40",
        "execute at @a run fill ~-1 ~ ~1 ~1 ~2 ~3 minecraft:air",
        "execute at @a run fill ~-1 ~-1 ~1 ~1 ~-1 ~3 minecraft:gold_block",
        // A named villager standing in a pit ahead, so its name tag is in view (name tag layer).
        "execute at @a run fill ~-1 ~-4 ~3 ~-1 ~-4 ~3 minecraft:stone",
        "execute at @a run fill ~-1 ~-3 ~3 ~-1 ~-1 ~3 minecraft:air",
        "execute at @a run summon minecraft:villager ~-1 ~-3 ~3 {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomNameVisible:1b,CustomName:\"Name tag layer\"}",
        "bossbar add layercast:test \"LayerCast Boss\"",
        "bossbar set layercast:test players @a",
        "bossbar set layercast:test color purple",
        "scoreboard objectives add lc dummy \"LayerCast\"",
        "scoreboard objectives setdisplay sidebar lc",
        "scoreboard objectives setdisplay list lc",
        "scoreboard players set @a lc 42",
        "scoreboard players set Alex lc 7",
        "effect give @a minecraft:speed infinite 1",
        "effect give @a minecraft:night_vision infinite 0",
        "title @a times 10 100000 10",
        "title @a subtitle \"subtitle layer\"",
        "title @a title \"LayerCast\"",
        "title @a actionbar \"action bar layer\"",
        "say Hello from the chat layer");

    private enum Step { WAIT_TITLE, CREATING, IN_WORLD, WAIT_CONSUMER, DUMPING, PLAIN, CONFIG_GUI, INVENTORY, HOLDING, DONE }

    private final Path out = Path.of(System.getProperty("layercast.test.out", "layercast-test"));
    private final int holdSeconds = Integer.getInteger("layercast.test.holdSeconds", 0);
    private Step step = Step.WAIT_TITLE;
    private int ticks;

    public LayerCastDevTest() {
        if (Boolean.getBoolean("layercast.test.autorun")) {
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> this.tick(Minecraft.getInstance()));
        }
    }

    private void next(Step next) {
        LayerCast.LOGGER.info("[devtest] {} -> {}", this.step, next);
        this.step = next;
        this.ticks = 0;
    }

    private void tick(Minecraft mc) {
        this.ticks++;
        switch (this.step) {
            case WAIT_TITLE -> {
                if (!(screen(mc) instanceof TitleScreen) && mc.level == null && this.ticks > 60) {
                    setScreen(mc, new TitleScreen()); // skips first-launch onboarding screens
                }
                if (screen(mc) instanceof TitleScreen && this.ticks > 20) {
                    CreateWorldScreen.openFresh(mc, () -> setScreen(mc, new TitleScreen()));
                    if (screen(mc) instanceof CreateWorldScreen screen) {
                        String create = Component.translatable("selectWorld.create").getString();
                        screen.children().stream()
                            .filter(child -> child instanceof Button b && b.getMessage().getString().equals(create))
                            .findFirst()
                            .ifPresentOrElse(b -> ((Button) b).onPress(new MouseButtonInfo(-1, 0)),
                                () -> LayerCast.LOGGER.error("[devtest] create button not found"));
                    }
                    this.next(Step.CREATING);
                }
            }
            case CREATING -> {
                if (mc.level != null && mc.player != null && screen(mc) == null && this.ticks > 40) {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server != null) {
                        server.execute(() -> COMMANDS.forEach(cmd ->
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd)));
                    }
                    mc.debugEntries.setOverlayVisible(true);
                    mc.options.keyPlayerList.setDown(true);
                    this.next(Step.IN_WORLD);
                }
            }
            case IN_WORLD -> {
                if (this.ticks > 40) {
                    this.next(this.holdSeconds > 0 && !Boolean.getBoolean("layercast.forceAll") ? Step.WAIT_CONSUMER : Step.DUMPING);
                    if (this.step == Step.DUMPING) {
                        LayerDebug.requestDump(this.out.resolve("layers"));
                    }
                }
            }
            case WAIT_CONSUMER -> {
                if (LayerCast.sharing() != null && LayerCast.sharing().activeLayerCount() > 0 && this.ticks > 20) {
                    LayerDebug.requestDump(this.out.resolve("layers"));
                    this.next(Step.DUMPING);
                } else if (this.ticks > 60 * 20) {
                    LayerCast.LOGGER.error("[devtest] no OBS consumer connected within 60 s");
                    this.next(Step.DUMPING);
                }
            }
            case DUMPING -> {
                if (!LayerDebug.dumpInProgress() && this.ticks > 5) {
                    Screenshot.grab(mc.gameDirectory, "layercast-neoforge-full-frame.png", RenderCompat.mainRenderTarget(), 1,
                        message -> LayerCast.LOGGER.info("[devtest] {}", message.getString()));
                    if (LayerCast.sharing() != null) {
                        LayerCast.sharing().describe().forEach(line -> LayerCast.LOGGER.info("[devtest] {}", line));
                    }
                    // Many HUD mods hide themselves while F3 is open: dump once more without F3 and Tab.
                    mc.options.keyPlayerList.setDown(false);
                    mc.debugEntries.setOverlayVisible(false);
                    this.next(Step.PLAIN);
                }
            }
            case PLAIN -> {
                if (this.ticks == 10) {
                    ModAttribution.takeStats();
                } else if (this.ticks == 20) {
                    LayerCast.LOGGER.info("[devtest] layers: {}; mod attribution over 0.5 s: {}",
                        Layers.all().stream().map(Layer::id).toList(), ModAttribution.takeStats());
                    LayerDebug.requestDump(this.out.resolve("layers-plain"));
                } else if (this.ticks > 25 && !LayerDebug.dumpInProgress()) {
                    Screenshot.grab(mc.gameDirectory, "layercast-neoforge-plain-frame.png", RenderCompat.mainRenderTarget(), 1,
                        message -> LayerCast.LOGGER.info("[devtest] {}", message.getString()));
                    GuiBase.openGui(new GuiConfigs());
                    this.next(Step.CONFIG_GUI);
                }
            }
            case CONFIG_GUI -> {
                if (this.ticks == 20) {
                    LayerDebug.requestDump(this.out.resolve("layers-config-gui"));
                } else if (this.ticks > 25 && !LayerDebug.dumpInProgress()) {
                    // The player preview in the inventory is a picture-in-picture element: layers reuse vanilla's texture.
                    setScreen(mc, new InventoryScreen(mc.player));
                    this.next(Step.INVENTORY);
                }
            }
            case INVENTORY -> {
                if (this.ticks == 20) {
                    LayerDebug.requestDump(this.out.resolve("layers-inventory"));
                } else if (this.ticks > 25 && !LayerDebug.dumpInProgress()) {
                    Screenshot.grab(mc.gameDirectory, "layercast-neoforge-inventory.png", RenderCompat.mainRenderTarget(), 1,
                        message -> LayerCast.LOGGER.info("[devtest] {}", message.getString()));
                    setScreen(mc, null);
                    mc.debugEntries.setOverlayVisible(true);
                    mc.options.keyPlayerList.setDown(true);
                    this.writeMarker();
                    this.next(Step.HOLDING);
                }
            }
            case HOLDING -> {
                if (this.ticks > this.holdSeconds * 20) {
                    LayerCast.LOGGER.info("[devtest] finished");
                    this.next(Step.DONE);
                    mc.stop();
                }
            }
            case DONE -> {
            }
        }
    }

    // Screen management moved from Minecraft to Gui in 26.2.
    private static net.minecraft.client.gui.screens.Screen screen(Minecraft mc) {
        //? if >=26.2 {
        return mc.gui.screen();
        //?} else
        /*return mc.screen;*/
    }

    private static void setScreen(Minecraft mc, net.minecraft.client.gui.screens.Screen screen) {
        //? if >=26.2 {
        mc.gui.setScreen(screen);
        //?} else
        /*mc.setScreen(screen);*/
    }

    private void writeMarker() {
        try {
            Files.createDirectories(this.out);
            Files.writeString(this.out.resolve("READY"), Long.toString(System.currentTimeMillis()));
        } catch (Exception e) {
            LayerCast.LOGGER.error("[devtest] could not write marker", e);
        }
    }
}
