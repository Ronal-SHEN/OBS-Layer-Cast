package starship.layercast.mixin.scope;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import starship.layercast.layer.Layer;
import starship.layercast.layer.LayerCapture;
import starship.layercast.layer.Layers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks which layer the elements extracted by each vanilla HUD component belong to. These private
 * {@code Hud} ({@code Gui} before 26.2) methods exist unchanged on Fabric and NeoForge (NeoForge calls them from its GUI layers).
 */
//? if >=26.2 {
@Mixin(net.minecraft.client.gui.Hud.class)
//?} else
/*@Mixin(net.minecraft.client.gui.Gui.class)*/
abstract class HudScopeMixin {
    private static void layercast$scoped(Layer layer, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        LayerCapture.enter(layer);
        try {
            original.call(graphics, deltaTracker);
        } finally {
            LayerCapture.exit();
        }
    }

    /** HUD elements that none of the component scopes below claims are attributed to the mod drawing them. */
    @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
    private void layercast$hud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        LayerCapture.enterUnclaimed();
        try {
            original.call(graphics, deltaTracker);
        } finally {
            LayerCapture.exit();
        }
    }

    /**
     * Subtitles are extracted after the rest of the GUI; HUD elements that Fabric API appends to them
     * (see {@code SubtitleOverlayScopeMixin}) are attributed to their mods like every other mod HUD element.
     */
    @WrapMethod(method = "extractDeferredSubtitles")
    private void layercast$deferredSubtitles(Operation<Void> original) {
        LayerCapture.enterUnclaimed();
        try {
            original.call();
        } finally {
            LayerCapture.exit();
        }
    }

    @WrapMethod(method = "extractCameraOverlays")
    private void layercast$camera(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.CAMERA, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractSleepOverlay")
    private void layercast$sleep(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.CAMERA, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractCrosshair")
    private void layercast$crosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.CROSSHAIR, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractEffects")
    private void layercast$effects(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.EFFECTS, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractBossOverlay")
    private void layercast$bossbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.BOSSBAR, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractScoreboardSidebar")
    private void layercast$scoreboard(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.SCOREBOARD, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractOverlayMessage")
    private void layercast$actionbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.ACTIONBAR, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractTitle")
    private void layercast$title(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.TITLE, graphics, deltaTracker, original);
    }

    @WrapMethod(method = "extractTabList")
    private void layercast$tablist(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        layercast$scoped(Layers.TABLIST, graphics, deltaTracker, original);
    }
}
