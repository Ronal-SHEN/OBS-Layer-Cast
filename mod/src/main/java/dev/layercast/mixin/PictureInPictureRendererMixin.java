package dev.layercast.mixin;

import dev.layercast.layer.LayerRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers which renderer drew each picture-in-picture element (entity previews, minimaps, ...) of the vanilla
 * GUI, so the layers can reuse its texture instead of rendering the element again.
 */
@Mixin(PictureInPictureRenderer.class)
abstract class PictureInPictureRendererMixin {
    @Inject(method = "blitTexture", at = @At("HEAD"))
    private void layercast$rememberBlit(PictureInPictureRenderState state, GuiRenderState guiRenderState, CallbackInfo ci) {
        LayerRenderer.onPictureInPictureBlit((PictureInPictureRenderer<?>) (Object) this, state, guiRenderState);
    }
}
