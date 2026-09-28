package starship.layercast.share.transport;

//? if >=26.2 {
import starship.layercast.share.Protocol;
import starship.layercast.share.ffm.MacOS;
import starship.layercast.share.vk.VulkanInterop;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImportMetalIOSurfaceInfoEXT;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * macOS + Vulkan (MoltenVK) backend: {@code VK_EXT_metal_objects} creates each slot's {@code VkImage} on top of a
 * global IOSurface, so OBS sees exactly the same surfaces as with the OpenGL backend.
 */
public final class MacVkIOSurfaceTransport extends VulkanImageTransport {
    private MemorySegment[] surfaces = new MemorySegment[0];

    public MacVkIOSurfaceTransport() {
        if (!VulkanInterop.enabled(VulkanInterop.EXT_METAL_OBJECTS)) {
            throw new UnsupportedOperationException("VK_EXT_metal_objects is not enabled on the Vulkan device");
        }
        MacOS.load();
    }

    @Override
    public int protocolId() {
        return Protocol.TRANSPORT_IOSURFACE;
    }

    @Override
    public int format() {
        return Protocol.FORMAT_BGRA8;
    }

    @Override
    protected boolean externalOwnership() {
        return false;
    }

    @Override
    protected boolean needsBlit() {
        return true; // RGBA source -> BGRA IOSurface
    }

    @Override
    public long[] allocate(int width, int height, int slots) {
        this.release();
        var device = VulkanInterop.vkDevice();
        long[] images = new long[slots];
        long[] memories = new long[slots];
        MemorySegment[] surfaces = new MemorySegment[slots];
        long[] handles = new long[slots];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < slots; i++) {
                surfaces[i] = MacOS.createGlobalSurface(width, height);
                handles[i] = Integer.toUnsignedLong(MacOS.surfaceId(surfaces[i]));

                VkImportMetalIOSurfaceInfoEXT importInfo = VkImportMetalIOSurfaceInfoEXT.calloc(stack).sType$Default()
                    .ioSurface(surfaces[i].address());
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .pNext(importInfo.address())
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_B8G8R8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width, height, 1);
                LongBuffer pImage = stack.mallocLong(1);
                VulkanInterop.check(vkCreateImage(device, info, null, pImage), "vkCreateImage(IOSurface)");
                images[i] = pImage.get(0);

                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(device, images[i], requirements);
                VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(images[i]);
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(dedicated.address())
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(VulkanInterop.findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                LongBuffer pMemory = stack.mallocLong(1);
                VulkanInterop.check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory");
                memories[i] = pMemory.get(0);
                VulkanInterop.check(vkBindImageMemory(device, images[i], memories[i], 0L), "vkBindImageMemory");
            }
        } catch (RuntimeException e) {
            this.setSlots(images, memories, 0, 0);
            this.surfaces = surfaces;
            this.release();
            throw e;
        }
        this.setSlots(images, memories, width, height);
        this.surfaces = surfaces;
        return handles;
    }

    private void release() {
        MemorySegment[] surfaces = this.surfaces;
        this.surfaces = new MemorySegment[0];
        this.destroyImages(() -> {
            for (MemorySegment surface : surfaces) {
                MacOS.release(surface);
            }
        });
    }

    @Override
    public void close() {
        this.release();
    }
}
//?}
