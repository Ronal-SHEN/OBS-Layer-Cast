package dev.layercast.share.transport;

//? if >=26.2 {
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.layercast.share.vk.VulkanInterop;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Common part of the Vulkan transports: each slot is a {@code VkImage} whose memory is shared with another API
 * (IOSurface / D3D11). The copy is recorded straight into Minecraft's command buffer for the current submit, so it
 * executes in order after the layer was drawn, and the frame fence Minecraft already uses tells us when it is done.
 */
abstract class VulkanImageTransport implements Transport {
    protected long[] images = new long[0];
    protected long[] memories = new long[0];
    private boolean[] initialized = new boolean[0];
    protected int width;
    protected int height;

    /** Whether the images are owned by an external API and need queue family ownership transfers. */
    protected abstract boolean externalOwnership();

    /** Whether the shared format differs from the RGBA8 source and needs a converting blit. */
    protected abstract boolean needsBlit();

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return this.height;
    }

    @Override
    public boolean flipY() {
        // Minecraft's Vulkan backend keeps the OpenGL convention: row 0 is the bottom of the picture.
        return true;
    }

    protected void setSlots(long[] images, long[] memories, int width, int height) {
        this.images = images;
        this.memories = memories;
        this.initialized = new boolean[images.length];
        this.width = width;
        this.height = height;
    }

    @Override
    public void copy(GpuTexture source, int slot) {
        long src = ((VulkanGpuTexture) source).vkImage();
        long dst = this.images[slot];
        VkCommandBuffer cmd = VulkanInterop.commandBuffer();
        int family = VulkanInterop.graphicsQueueFamily();
        boolean external = this.externalOwnership() && this.initialized[slot];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer acquire = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(this.initialized[slot] ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(external ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(external ? family : VK_QUEUE_FAMILY_IGNORED)
                .image(dst);
            acquire.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, acquire);

            if (this.needsBlit()) {
                VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffsets(1).set(this.width, this.height, 1);
                region.dstOffsets(1).set(this.width, this.height, 1);
                vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, region, VK_FILTER_NEAREST);
            } else {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.extent().set(this.width, this.height, 1);
                vkCmdCopyImage(cmd, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, region);
            }

            // Hand the image to the external consumer (no-op ownership-wise on MoltenVK) ...
            VkImageMemoryBarrier.Buffer release = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(0)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(this.externalOwnership() ? family : VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(this.externalOwnership() ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED)
                .image(dst);
            release.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            // ... and make sure Minecraft's next write to the source (next layer / next frame) waits for our read.
            VkMemoryBarrier.Buffer readDone = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, readDone, null, release);
        }
        this.initialized[slot] = true;
    }

    /**
     * Frees the images once the GPU is done with them (copies of the last frames may still be in flight):
     * the work is queued on Minecraft's own deferred-destruction queue, which runs two submits later.
     * {@code alsoRelease} disposes the external side (IOSurface / D3D11 texture) at the same time.
     */
    protected void destroyImages(Runnable alsoRelease) {
        long[] images = this.images;
        long[] memories = this.memories;
        this.images = new long[0];
        this.memories = new long[0];
        this.initialized = new boolean[0];
        if (images.length == 0) {
            alsoRelease.run();
            return;
        }
        var device = VulkanInterop.vkDevice();
        VulkanInterop.device().createCommandEncoder().queueForDestroy(() -> {
            for (int i = 0; i < images.length; i++) {
                if (images[i] != 0L) {
                    VK12.vkDestroyImage(device, images[i], null);
                }
                if (memories[i] != 0L) {
                    VK12.vkFreeMemory(device, memories[i], null);
                }
            }
            alsoRelease.run();
        });
    }
}
//?}
