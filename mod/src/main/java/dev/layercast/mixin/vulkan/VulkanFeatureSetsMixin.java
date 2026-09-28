package dev.layercast.mixin.vulkan;

//? if >=26.3 {
/*import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vulkan.init.FeatureSet;
import com.mojang.blaze3d.vulkan.VulkanFeatureSets;
import dev.layercast.share.vk.VulkanInterop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/^*
 * 26.3+ builds the Vulkan device from feature sets; an optional set is enabled by Minecraft itself when the GPU
 * supports all of its extensions, which is exactly what zero-copy sharing needs.
 ^/
@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {
    @ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
    private static Set<FeatureSet> layercast$addSharingExtensions(Set<FeatureSet> sets) {
        if (!VulkanInterop.wantedExtensions().isEmpty()) {
            sets.add(new FeatureSet("OBS Layer Cast sharing", Set.copyOf(VulkanInterop.wantedExtensions()), Set.of()));
        }
        return sets;
    }
}
*///?}
