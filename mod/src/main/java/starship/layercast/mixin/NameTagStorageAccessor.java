package starship.layercast.mixin;

//? if <26.2 {
/*import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/^* The name tags of a submit collection (26.1), to skip the name tag capture when there are none. ^/
@Mixin(NameTagFeatureRenderer.Storage.class)
public interface NameTagStorageAccessor {
    @Accessor("nameTagSubmitsSeethrough")
    List<?> layercast$seeThrough();

    @Accessor("nameTagSubmitsNormal")
    List<?> layercast$normal();
}
*///?}
