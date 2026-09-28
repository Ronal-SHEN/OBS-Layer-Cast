package starship.layercast.mixin.vulkan;

//? if >=26.2 {
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Gives access to the command buffer of the current submit, so layer copies are recorded in frame order. */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderInvoker {
    @Invoker("commandBuffer")
    VkCommandBuffer layercast$commandBuffer();
}
//?}
