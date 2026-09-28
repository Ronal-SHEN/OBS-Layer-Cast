package starship.layercast.mixin;

import starship.layercast.layer.LayerCapture;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tees GUI elements submitted to the vanilla render state into the layer render state selected by
 * {@link LayerCapture}. Element render states are immutable descriptions, so sharing the same instance is safe.
 * Stratum changes go to every recorded layer, so each keeps vanilla's ordering.
 */
@Mixin(GuiRenderState.class)
abstract class GuiRenderStateMixin {
    @Inject(method = "addGuiElement", at = @At("HEAD"))
    private void layercast$teeElement(GuiElementRenderState state, CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            GuiRenderState target = LayerCapture.target();
            if (target != null) {
                target.addGuiElement(state);
            }
        }
    }

    @Inject(method = "addItem", at = @At("HEAD"))
    private void layercast$teeItem(GuiItemRenderState state, CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            GuiRenderState target = LayerCapture.target();
            if (target != null) {
                target.addItem(state);
            }
        }
    }

    @Inject(method = "addText", at = @At("HEAD"))
    private void layercast$teeText(GuiTextRenderState state, CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            GuiRenderState target = LayerCapture.target();
            if (target != null) {
                target.addText(state);
            }
        }
    }

    @Inject(method = "addPicturesInPictureState", at = @At("HEAD"))
    private void layercast$teePictureInPicture(PictureInPictureRenderState state, CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            GuiRenderState target = LayerCapture.target();
            if (target != null) {
                target.addPicturesInPictureState(state);
            }
        }
    }

    /** Used by mods to blit their own render targets during extraction (JourneyMap's map). */
    @Inject(method = "addBlitToCurrentLayer", at = @At("HEAD"))
    private void layercast$teeBlit(BlitRenderState state, CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            GuiRenderState target = LayerCapture.target();
            if (target != null) {
                target.addBlitToCurrentLayer(state);
            }
        }
    }

    @Inject(method = "addGlyphToCurrentLayer", at = @At("HEAD"))
    private void layercast$teeGlyph(GuiElementRenderState state, CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            GuiRenderState target = LayerCapture.target();
            if (target != null) {
                target.addGlyphToCurrentLayer(state);
            }
        }
    }

    @Inject(method = "nextStratum", at = @At("HEAD"))
    private void layercast$teeStratum(CallbackInfo ci) {
        if (LayerCapture.isRecording((GuiRenderState) (Object) this)) {
            LayerCapture.forEachRecording(GuiRenderState::nextStratum);
        }
    }
}
