package starship.layercast.mixin;

import starship.layercast.LayerCast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the routing window at the start of the GUI extraction ({@code Gui.extractRenderState} since 26.2,
 * {@code GameRenderer.extractGui} before). The window is closed only when {@code GameRenderer.extract} returns
 * (see {@link GameRendererMixin}), so HUDs that mods draw from injections at the end of this method (MaLiLib's
 * overlay event, used by MiniHUD and friends, injects at its TAIL) are captured too.
 */
//? if >=26.2 {
@Mixin(net.minecraft.client.gui.Gui.class)
//?} else
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)*/
abstract class GuiMixin {
    //? if >=26.2 {
    private static final String EXTRACT = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V";
    //?} else
    /*private static final String EXTRACT = "extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V";*/

    @Inject(method = EXTRACT, at = @At("HEAD"))
    private void layercast$startRecording(CallbackInfo ci) {
        LayerCast.onGuiExtractStart();
    }

    /**
     * {@code applyCursor} is the last thing vanilla does; everything extracted after it comes from other mods and
     * belongs to no vanilla component. Optional: without it those elements still reach the {@code gui} layer.
     */
    @Inject(method = EXTRACT, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;applyCursor"), require = 0)
    private void layercast$vanillaGuiDone(CallbackInfo ci) {
        LayerCast.onVanillaGuiExtracted();
    }
}
