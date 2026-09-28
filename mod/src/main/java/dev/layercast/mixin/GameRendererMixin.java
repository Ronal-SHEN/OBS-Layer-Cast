package dev.layercast.mixin;

import dev.layercast.LayerCast;
import dev.layercast.compat.RenderCompat;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if <26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.gui.render.GuiRenderer;
*///?}

/**
 * Frame hooks (handlers only take the CallbackInfo, so they don't depend on the parameter lists, which changed in
 * 26.3): decide what to capture before extraction, stop routing GUI elements once extraction is over and render the
 * layers right after the vanilla GUI (the world is grabbed in {@code GuiRendererMixin}).
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void layercast$beginFrame(CallbackInfo ci) {
        LayerCast.onFrameStart();
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void layercast$endExtract(CallbackInfo ci) {
        LayerCast.onGuiExtractEnd();
    }

    //? if <26.2 {
    /*/^* 26.1 passes the fog buffer to GuiRenderer.render; layers draw with the same one. ^/
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void layercast$guiFog(GuiRenderer renderer, GpuBufferSlice fogBuffer, Operation<Void> original) {
        RenderCompat.setGuiFogBuffer(fogBuffer);
        original.call(renderer, fogBuffer);
    }
    *///?}

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void layercast$levelStart(CallbackInfo ci) {
        LayerCast.onLevelStart();
    }

    /** The world and the first-person hand are drawn (name tags are captured in between). */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void layercast$levelEnd(CallbackInfo ci) {
        LayerCast.onLevelEnd(RenderCompat.mainRenderTarget());
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V", shift = At.Shift.AFTER))
    private void layercast$afterGui(CallbackInfo ci) {
        LayerCast.onAfterGuiRender(RenderCompat.mainRenderTarget());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void layercast$close(CallbackInfo ci) {
        LayerCast.onRendererClose();
    }
}
