package dev.layercast.mixin.scope;

import dev.layercast.layer.LayerCapture;
import dev.layercast.layer.Layers;
import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Subtitles are extracted after the HUD (deferred), so they need their own scope. Unlike the other scopes this
 * one is injected into the method body instead of wrapping it: Fabric API wraps this method to extract every HUD
 * element that mods append after the vanilla ones ({@code HudElementRegistry.addLast}), and those must not end up
 * in the subtitles layer (method wrappers always enclose injections, whatever the priorities).
 */
@Mixin(SubtitleOverlay.class)
abstract class SubtitleOverlayScopeMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void layercast$enterSubtitles(CallbackInfo ci) {
        LayerCapture.enter(Layers.SUBTITLES);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void layercast$exitSubtitles(CallbackInfo ci) {
        LayerCapture.exit();
    }
}
