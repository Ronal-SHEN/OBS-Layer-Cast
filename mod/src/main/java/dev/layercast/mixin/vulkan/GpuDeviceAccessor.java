package dev.layercast.mixin.vulkan;

//? if >=26.2 {
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 26.3 made GpuDevice an interface; the backend lives in its FrontendGpuDevice implementation.
//? if >=26.3 {
/*@Mixin(com.mojang.renderpearl.frontend.FrontendGpuDevice.class)
*///?} else
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend layercast$backend();
}
//?}
