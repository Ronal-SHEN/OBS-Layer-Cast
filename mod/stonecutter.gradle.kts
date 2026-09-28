plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2-fabric"

stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)

    properties {
        tags(version, loader)
    }

    // Enables `//? if fabric {` / `//? if neoforge {` in sources.
    constants {
        match(loader, "fabric", "neoforge")
    }

    swaps["mod_version"] = "\"${properties.get<String>("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"

    // 26.3 moved Minecraft's GPU abstraction from com.mojang.blaze3d.* into com.mojang.renderpearl.*.
    // Sources are written against 26.2; these rewrite both dotted names and JVM descriptors.
    replacements {
        string(current.parsed >= "26.3") {
            val relocations = listOf(
                "com.mojang.blaze3d.GpuFormat" to "com.mojang.renderpearl.api.GpuFormat",
                "com.mojang.blaze3d.buffers.GpuBufferSlice" to "com.mojang.renderpearl.api.buffers.GpuBufferSlice",
                "com.mojang.blaze3d.buffers.GpuBuffer" to "com.mojang.renderpearl.api.buffers.GpuBuffer",
                "com.mojang.blaze3d.buffers.GpuFence" to "com.mojang.renderpearl.api.commands.GpuFence",
                "com.mojang.blaze3d.pipeline.RenderPipeline" to "com.mojang.renderpearl.api.pipeline.RenderPipeline",
                "com.mojang.blaze3d.pipeline.BindGroupLayout" to "com.mojang.renderpearl.api.pipeline.BindGroupLayout",
                "com.mojang.blaze3d.systems.RenderPass" to "com.mojang.renderpearl.api.commands.RenderPass",
                "com.mojang.blaze3d.opengl." to "com.mojang.renderpearl.backend.opengl.",
                "com.mojang.blaze3d.systems.GpuDeviceBackend" to "com.mojang.renderpearl.backend.api.GpuDeviceBackend",
                "com.mojang.blaze3d.systems.GpuDevice" to "com.mojang.renderpearl.api.device.GpuDevice",
                "com.mojang.blaze3d.shaders.GpuDebugOptions" to "com.mojang.renderpearl.api.device.GpuDebugOptions",
                "com.mojang.blaze3d.shaders.ShaderSource" to "com.mojang.renderpearl.api.pipeline.ShaderSource",
                "com.mojang.blaze3d.textures." to "com.mojang.renderpearl.api.textures.",
                "com.mojang.blaze3d.vulkan." to "com.mojang.renderpearl.backend.vulkan.",
            )
            for ((from, to) in relocations) {
                replace(from, to)
                replace(from.replace('.', '/'), to.replace('.', '/'))
            }
        }
    }
}
