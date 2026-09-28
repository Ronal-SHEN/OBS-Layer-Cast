package dev.layercast.mixin.scope;

//? if fabric {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.layercast.layer.LayerCapture;
import dev.layercast.layer.Layers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla (and Fabric) extract the hotbar, health/armor/food/air bars, experience bar and held item name in one
 * method; NeoForge splits it into separate GUI layers and is handled by its own event hook instead.
 */
//? if >=26.2 {
@Mixin(net.minecraft.client.gui.Hud.class)
//?} else
/*@Mixin(net.minecraft.client.gui.Gui.class)*/
abstract class HotbarScopeMixin {
    @WrapMethod(method = "extractHotbarAndDecorations")
    private void layercast$hotbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        LayerCapture.enter(Layers.HOTBAR);
        try {
            original.call(graphics, deltaTracker);
        } finally {
            LayerCapture.exit();
        }
    }
}
//?}
