package dev.layercast.mixin;

//? if <26.3 {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.layercast.LayerCast;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;

//? if >=26.2 {
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;

import java.util.List;
//?} else {
/*import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
*///?}

/**
 * Draws the name tags a second time into the name tag layer when it is split out (see {@code NameTagLayer}).
 * <p>
 * 26.2 draws them in groups (see-through parts, then the others, sorted by distance) from vertices prepared for the
 * whole frame, so each group is simply drawn again. 26.1 builds them into a batching buffer source whose batches are
 * drawn when the next render type is requested or at the end of the translucent features: the batch pending before
 * the name tags is drawn first (it would be at the first name tag anyway), then the name tags are built and drawn
 * right away, once for the game and once for the layer. 26.3 draws name tags together with all other text in the
 * world and has no name tag layer.
 */
//? if >=26.2 {
@Mixin(RenderTypeFeatureRenderer.class)
//?} else
/*@Mixin(NameTagFeatureRenderer.class)*/
abstract class NameTagRendererMixin {
    //? if >=26.2 {
    @WrapMethod(method = "executeGroup")
    private void layercast$nameTags(FeatureFrameContext context, int groupIndex, List<?> submits, boolean strictlyOrdered,
                                    Operation<Void> original) {
        if ((Object) this instanceof NameTagFeatureRenderer && LayerCast.capturingNameTags()) {
            LayerCast.onNameTags(() -> original.call(context, groupIndex, submits, strictlyOrdered));
        } else {
            original.call(context, groupIndex, submits, strictlyOrdered);
        }
    }
    //?} else {
    /*@WrapMethod(method = "renderTranslucent")
    private void layercast$nameTags(SubmitNodeCollection collection, MultiBufferSource.BufferSource bufferSource, Font font,
                                    Operation<Void> original) {
        NameTagStorageAccessor storage = (NameTagStorageAccessor) collection.getNameTagSubmits();
        if (!LayerCast.capturingNameTags() || (storage.layercast$seeThrough().isEmpty() && storage.layercast$normal().isEmpty())) {
            original.call(collection, bufferSource, font);
            return;
        }
        bufferSource.endLastBatch();
        LayerCast.onNameTags(() -> {
            original.call(collection, bufferSource, font);
            bufferSource.endLastBatch();
        });
    }
    *///?}
}
//?}
