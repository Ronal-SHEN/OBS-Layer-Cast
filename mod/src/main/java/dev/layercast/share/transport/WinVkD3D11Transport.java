package dev.layercast.share.transport;

//? if >=26.2 {
import dev.layercast.LayerCast;
import dev.layercast.share.Protocol;
import dev.layercast.share.ffm.Windows;
import dev.layercast.share.vk.VulkanInterop;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImportMemoryWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkMemoryWin32HandlePropertiesKHR;

import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Windows + Vulkan backend: textures are created by a private D3D11 device on the same GPU (matched by LUID),
 * shared through NT handles, and imported into Minecraft's Vulkan device
 * ({@code VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT}). OBS opens the same textures with
 * {@code gs_texture_open_nt_shared} after duplicating the handle out of this process.
 */
public final class WinVkD3D11Transport extends VulkanImageTransport {
    private static final int HANDLE_TYPE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT;

    private static Windows.D3D11Device sharedDevice;
    private static int references;

    private final Windows.D3D11Device d3d;
    private MemorySegment[] textures = new MemorySegment[0];
    private long[] ntHandles = new long[0];

    public WinVkD3D11Transport() {
        if (!VulkanInterop.enabled(VulkanInterop.KHR_EXTERNAL_MEMORY_WIN32)) {
            throw new UnsupportedOperationException("VK_KHR_external_memory_win32 is not enabled on the Vulkan device");
        }
        this.d3d = acquireDevice();
    }

    private static Windows.D3D11Device acquireDevice() {
        if (sharedDevice == null) {
            Windows.load();
            long luid = VulkanInterop.deviceLuid();
            MemorySegment adapter = luid != 0L ? Windows.findAdapter(luid) : MemorySegment.NULL;
            try {
                sharedDevice = Windows.createDevice(adapter);
            } finally {
                Windows.release(adapter);
            }
            LayerCast.LOGGER.info("Created D3D11 device for Vulkan sharing (LUID {}, adapter matched: {})",
                Long.toHexString(luid), adapter.address() != 0L);
        }
        references++;
        return sharedDevice;
    }

    private static void releaseDevice() {
        if (--references == 0 && sharedDevice != null) {
            Windows.release(sharedDevice.context());
            Windows.release(sharedDevice.device());
            sharedDevice = null;
        }
    }

    @Override
    public int protocolId() {
        return Protocol.TRANSPORT_D3D11_NT;
    }

    @Override
    public int format() {
        return Protocol.FORMAT_RGBA8;
    }

    @Override
    protected boolean externalOwnership() {
        return true;
    }

    @Override
    protected boolean needsBlit() {
        return false;
    }

    @Override
    public long[] allocate(int width, int height, int slots) {
        this.release();
        var device = VulkanInterop.vkDevice();
        long[] images = new long[slots];
        long[] memories = new long[slots];
        MemorySegment[] textures = new MemorySegment[slots];
        long[] handles = new long[slots];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < slots; i++) {
                textures[i] = Windows.createNtSharedTexture(this.d3d.device(), width, height);
                handles[i] = Windows.createNtHandle(textures[i]);

                VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(HANDLE_TYPE);
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                    .pNext(external.address())
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width, height, 1);
                LongBuffer pImage = stack.mallocLong(1);
                VulkanInterop.check(vkCreateImage(device, info, null, pImage), "vkCreateImage(D3D11 import)");
                images[i] = pImage.get(0);

                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(device, images[i], requirements);
                int typeBits = requirements.memoryTypeBits();
                VkMemoryWin32HandlePropertiesKHR handleProps = VkMemoryWin32HandlePropertiesKHR.calloc(stack).sType$Default();
                if (KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR(device, HANDLE_TYPE, handles[i], handleProps) == VK_SUCCESS
                    && (typeBits & handleProps.memoryTypeBits()) != 0) {
                    typeBits &= handleProps.memoryTypeBits();
                }
                VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(images[i]);
                VkImportMemoryWin32HandleInfoKHR importInfo = VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .pNext(dedicated.address())
                    .handleType(HANDLE_TYPE)
                    .handle(handles[i]);
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(importInfo.address())
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(VulkanInterop.findMemoryType(typeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                LongBuffer pMemory = stack.mallocLong(1);
                VulkanInterop.check(vkAllocateMemory(device, alloc, null, pMemory), "vkAllocateMemory(D3D11 import)");
                memories[i] = pMemory.get(0);
                VulkanInterop.check(vkBindImageMemory(device, images[i], memories[i], 0L), "vkBindImageMemory");
            }
        } catch (RuntimeException e) {
            this.setSlots(images, memories, 0, 0);
            this.textures = textures;
            this.ntHandles = handles;
            this.release();
            throw e;
        }
        this.setSlots(images, memories, width, height);
        this.textures = textures;
        this.ntHandles = handles;
        return handles.clone();
    }

    private void release() {
        MemorySegment[] textures = this.textures;
        long[] handles = this.ntHandles;
        this.textures = new MemorySegment[0];
        this.ntHandles = new long[0];
        this.destroyImages(() -> {
            for (long handle : handles) {
                Windows.closeHandle(handle);
            }
            for (MemorySegment texture : textures) {
                Windows.release(texture);
            }
        });
    }

    @Override
    public void close() {
        this.release();
        releaseDevice();
    }
}
//?}
