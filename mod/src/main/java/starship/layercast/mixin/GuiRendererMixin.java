package starship.layercast.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import starship.layercast.LayerCast;
import starship.layercast.compat.RenderCompat;
import starship.layercast.layer.LayerPipelines;
import starship.layercast.layer.LayerRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a layer is being replayed, sends the GUI draws into the layer's off-screen target instead of the main
 * target, and skips the menu background blur (which would otherwise blur the player's screen a second time).
 * Picture-in-picture elements are not rendered again for a layer: the layer blits the texture the vanilla renderer
 * produced for the same element this frame (see {@link LayerRenderer#blitPictureInPicture}).
 */
@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @Shadow
    @Final
    private GuiRenderState renderState;

    @Shadow
    protected abstract void submitBlitFromItemAtlas(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView);

    /** Remembers where the vanilla GUI's item atlas holds each item of this frame. */
    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"))
    private void layercast$rememberItem(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        if (this.renderState == RenderCompat.mainGuiRenderState()) {
            LayerRenderer.onItemBlit(itemState, slotView);
        }
    }

    /**
     * Items are not rendered again for a layer either: the layer blits the item atlas slot (or the oversized-item
     * texture) the vanilla GUI used for the same item this frame, so they look exactly the same.
     */
    @WrapMethod(method = "prepareItemElements")
    private void layercast$reuseItems(Operation<Void> original) {
        if (LayerRenderer.redirectTarget() == null) {
            original.call();
            return;
        }
        GuiRenderState layerState = this.renderState;
        layerState.forEachItem(item -> {
            GuiItemAtlas.SlotView slot = LayerRenderer.vanillaItemSlot(item);
            if (slot != null) {
                this.submitBlitFromItemAtlas(item, slot);
            } else {
                LayerRenderer.blitOversizedItem(item, layerState);
            }
        });
    }

    //? if >=26.2 {
    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget layercast$redirectTarget(GameRenderer owner, Operation<RenderTarget> original) {
    //?} else {
    /*@WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget layercast$redirectTarget(net.minecraft.client.Minecraft owner, Operation<RenderTarget> original) {
    *///?}
        RenderTarget redirect = LayerRenderer.redirectTarget();
        return redirect != null ? redirect : original.call(owner);
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
    private void layercast$skipBlur(GameRenderer gameRenderer, Operation<Void> original) {
        if (LayerRenderer.redirectTarget() == null) {
            original.call(gameRenderer);
        }
    }

    /**
     * The vanilla GUI is about to be drawn: other mods' hooks on the start of {@code render()} have run, the main target
     * holds the finished world (layers never call {@code render()}, so this is always the vanilla GUI).
     */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;prepare()V"))
    private void layercast$beforeVanillaGui(CallbackInfo ci) {
        if (this.renderState == RenderCompat.mainGuiRenderState()) {
            LayerCast.onBeforeGuiRender(RenderCompat.mainRenderTarget());
        }
    }

    /** Elements whose blending needs an opaque picture below them are drawn differently into transparent layers. */
    @WrapOperation(method = "addElementToMesh", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiElementRenderState;pipeline()Lcom/mojang/blaze3d/pipeline/RenderPipeline;"))
    private RenderPipeline layercast$layerPipeline(GuiElementRenderState element, Operation<RenderPipeline> original) {
        RenderPipeline pipeline = original.call(element);
        return LayerRenderer.redirectTransparent() ? LayerPipelines.forLayer(pipeline) : pipeline;
    }

    @WrapMethod(method = "preparePictureInPicture")
    private void layercast$reusePictureInPicture(Operation<Void> original) {
        if (LayerRenderer.redirectTarget() == null) {
            original.call();
        } else {
            GuiRenderState layerState = this.renderState;
            layerState.forEachPictureInPicture(state -> LayerRenderer.blitPictureInPicture(state, layerState));
        }
    }
}
