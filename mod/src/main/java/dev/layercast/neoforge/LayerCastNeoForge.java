package dev.layercast.neoforge;

//? if neoforge {
/*import dev.layercast.LayerCast;
import dev.layercast.config.GuiConfigs;
import dev.layercast.config.LayerCastInit;
import dev.layercast.layer.LayerCapture;
import dev.layercast.layer.Layers;
import dev.layercast.platform.LoaderPlatform;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Set;

@Mod(value = LayerCast.MOD_ID, dist = Dist.CLIENT)
public final class LayerCastNeoForge {
    /^*
     * NeoForge splits vanilla's "hotbar and decorations" into these GUI layers; together they form the hotbar layer.
     * (The other HUD components are scoped by the common mixins on the vanilla methods these layers call.)
     ^/
    private static final Set<Identifier> HOTBAR_GROUP = Set.of(
        VanillaGuiLayers.HOTBAR, VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL,
        VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND,
        VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.CONTEXTUAL_INFO_BAR, VanillaGuiLayers.SELECTED_ITEM_NAME,
        VanillaGuiLayers.SPECTATOR_TOOLTIP);

    /^* Hotbar parts, and every GUI layer added by a mod (the other vanilla layers are scoped by the common mixins). ^/
    private static boolean scoped(Identifier layer) {
        return HOTBAR_GROUP.contains(layer)
            || !(layer.getNamespace().equals("minecraft") || layer.getNamespace().equals("neoforge"));
    }

    public LayerCastNeoForge(IEventBus modBus, ModContainer container) {
        LayerCast.init(new NeoForgePlatform());
        // MaFgLib is the NeoForge port of MaLiLib and exposes the same API.
        InitializationHandler.getInstance().registerInitializationHandler(new LayerCastInit());
        container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> {
            GuiConfigs gui = new GuiConfigs();
            gui.setParent(parent);
            return gui;
        });
        // Lowest priority: open the scope only if no other mod cancelled the layer (then Post is not fired either).
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderGuiLayerEvent.Pre.class, event -> {
            if (scoped(event.getName())) {
                if (HOTBAR_GROUP.contains(event.getName())) {
                    LayerCapture.enter(Layers.HOTBAR);
                } else {
                    // A mod's GUI layer: its namespace names the mod, no need to look at the stack.
                    String modId = event.getName().getNamespace();
                    if (LoaderPlatform.Holder.get().isModLoaded(modId)) {
                        LayerCapture.enterMod(modId);
                    } else {
                        LayerCapture.enterUnclaimed();
                    }
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderGuiLayerEvent.Post.class, event -> {
            if (scoped(event.getName())) {
                LayerCapture.exit();
            }
        });
    }
}
*///?}
