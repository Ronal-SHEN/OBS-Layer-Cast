package dev.layercast.mixin.scope;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.layercast.layer.LayerCapture;
import dev.layercast.layer.Layers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ToastManager.class)
abstract class ToastManagerScopeMixin {
    @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V")
    private void layercast$toasts(GuiGraphicsExtractor graphics, Operation<Void> original) {
        LayerCapture.enter(Layers.TOASTS);
        try {
            original.call(graphics);
        } finally {
            LayerCapture.exit();
        }
    }
}
