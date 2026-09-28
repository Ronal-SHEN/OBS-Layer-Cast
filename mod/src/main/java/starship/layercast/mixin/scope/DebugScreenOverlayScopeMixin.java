package starship.layercast.mixin.scope;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import starship.layercast.layer.LayerCapture;
import starship.layercast.layer.Layers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayScopeMixin {
    @WrapMethod(method = "extractRenderState")
    private void layercast$debug(GuiGraphicsExtractor graphics, Operation<Void> original) {
        LayerCapture.enter(Layers.DEBUG);
        try {
            original.call(graphics);
        } finally {
            LayerCapture.exit();
        }
    }
}
