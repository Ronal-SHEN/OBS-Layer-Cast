package starship.layercast.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * The steps of {@code GuiRenderer.render()}, so layers can be drawn without calling it: other mods hook that
 * method assuming it only runs for the vanilla GUI (Xaero's minimap draws its in-world waypoints into the main
 * target there), and must not run again for every layer.
 */
@Mixin(GuiRenderer.class)
public interface GuiRendererInternals {
    @Accessor("renderState")
    GuiRenderState layercast$renderState();

    @Accessor("draws")
    List<?> layercast$draws();

    @Accessor("firstDrawIndexAfterBlur")
    void layercast$setFirstDrawIndexAfterBlur(int index);

    @Invoker("prepare")
    void layercast$prepare();

    @Invoker("clearUnusedOversizedItemRenderers")
    void layercast$clearUnusedOversizedItemRenderers();

    //? if >=26.2 {
    @Accessor("vertexBuffer")
    net.minecraft.client.renderer.StagedVertexBuffer layercast$vertexBuffer();

    @Invoker("draw")
    void layercast$draw();
    //?} else {
    /*@Accessor("vertexBuffers")
    java.util.Map<com.mojang.blaze3d.vertex.VertexFormat, net.minecraft.client.renderer.MappableRingBuffer> layercast$vertexBuffers();

    @Accessor("meshesToDraw")
    List<?> layercast$meshesToDraw();

    @Invoker("draw")
    void layercast$draw(com.mojang.blaze3d.buffers.GpuBufferSlice fogBuffer);
    *///?}
}
