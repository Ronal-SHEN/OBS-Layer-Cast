package starship.layercast.share.vk;

//? if >=26.2 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import starship.layercast.LayerCast;
import starship.layercast.mixin.vulkan.GpuDeviceAccessor;
import starship.layercast.mixin.vulkan.VulkanCommandEncoderInvoker;
import starship.layercast.share.ffm.Native;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Access to Minecraft's Vulkan device for the Vulkan transports.
 */
public final class VulkanInterop {
    public static final String EXT_METAL_OBJECTS = "VK_EXT_metal_objects";
    public static final String KHR_EXTERNAL_MEMORY_WIN32 = "VK_KHR_external_memory_win32";
    public static final String KHR_EXTERNAL_MEMORY_FD = "VK_KHR_external_memory_fd";
    public static final String EXT_EXTERNAL_MEMORY_DMA_BUF = "VK_EXT_external_memory_dma_buf";

    private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();

    private VulkanInterop() {
    }

    public static List<String> wantedExtensions() {
        return switch (Native.OS) {
            case MACOS -> List.of(EXT_METAL_OBJECTS);
            case WINDOWS -> List.of(KHR_EXTERNAL_MEMORY_WIN32);
            case LINUX -> List.of(KHR_EXTERNAL_MEMORY_FD, EXT_EXTERNAL_MEMORY_DMA_BUF);
            default -> List.of();
        };
    }

    /** Called while Minecraft builds its device extension list. */
    public static void addDeviceExtensions(Collection<String> extensions, Predicate<String> supported) {
        for (String name : wantedExtensions()) {
            if (supported.test(name)) {
                extensions.add(name);
                ENABLED.add(name);
            } else {
                LayerCast.LOGGER.info("Vulkan device extension {} is not supported; layers will use the CPU fallback", name);
            }
        }
        if (!ENABLED.isEmpty()) {
            LayerCast.LOGGER.info("Enabled Vulkan device extensions for layer sharing: {}", ENABLED);
        }
    }

    /** Whether the extension is enabled on Minecraft's VkDevice (reads LWJGL's device capabilities). */
    public static boolean enabled(String extension) {
        var caps = vkDevice().getCapabilities();
        return switch (extension) {
            case EXT_METAL_OBJECTS -> caps.VK_EXT_metal_objects;
            case KHR_EXTERNAL_MEMORY_WIN32 -> caps.VK_KHR_external_memory_win32;
            case KHR_EXTERNAL_MEMORY_FD -> caps.VK_KHR_external_memory_fd;
            case EXT_EXTERNAL_MEMORY_DMA_BUF -> caps.VK_EXT_external_memory_dma_buf;
            default -> ENABLED.contains(extension);
        };
    }

    public static VulkanDevice device() {
        return (VulkanDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).layercast$backend();
    }

    public static VkDevice vkDevice() {
        return device().vkDevice();
    }

    /** The command buffer Minecraft is currently recording; commands land in frame order. */
    public static VkCommandBuffer commandBuffer() {
        return ((VulkanCommandEncoderInvoker) device().createCommandEncoder()).layercast$commandBuffer();
    }

    public static int graphicsQueueFamily() {
        return device().graphicsQueue().queueFamilyIndex();
    }

    /** LUID of the physical device (Windows), or 0 if not reported. */
    public static long deviceLuid() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id);
            VK12.vkGetPhysicalDeviceProperties2(vkDevice().getPhysicalDevice(), props);
            if (!id.deviceLUIDValid()) {
                return 0L;
            }
            return id.deviceLUID().order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(0);
        }
    }

    public static int findMemoryType(int typeBits, int requiredFlags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.malloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(vkDevice().getPhysicalDevice(), props);
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & requiredFlags) == requiredFlags) {
                    return i;
                }
            }
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("No compatible Vulkan memory type for bits 0x" + Integer.toHexString(typeBits));
    }

    public static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result);
        }
    }
}
//?}
