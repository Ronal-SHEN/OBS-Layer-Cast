package starship.layercast.mixin.vulkan;

//? if >=26.2 && <26.3 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import starship.layercast.share.vk.VulkanInterop;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.Set;

/**
 * Minecraft creates its VkDevice with a fixed extension list. External-memory extensions can only be enabled at
 * creation time, so the ones needed for zero-copy sharing are appended here when the GPU supports them.
 */
@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
    @WrapOperation(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
    private VkDevice layercast$enableExternalMemory(Collection<String> extensions, VulkanPhysicalDevice physicalDevice,
                                                    Set<VulkanFeature> features, Operation<VkDevice> original) {
        VulkanInterop.addDeviceExtensions(extensions, physicalDevice::hasDeviceExtension);
        return original.call(extensions, physicalDevice, features);
    }
}
//?}
