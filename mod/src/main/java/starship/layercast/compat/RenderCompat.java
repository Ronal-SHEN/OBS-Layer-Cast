package starship.layercast.compat;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import starship.layercast.mixin.GuiRendererInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

//? if >=26.2 {
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Vector4f;
import java.util.Optional;
//?} else {
/*import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.util.OptionalInt;
*///?}

/**
 * The small set of Minecraft rendering APIs whose shape differs between the supported versions. Everything else in
 * the mod is written against these helpers, so adding a Minecraft version mostly means extending this class and the
 * mixin targets (see {@code LayerCastMixins} in buildSrc).
 */
public final class RenderCompat {
    //? if >=26.2 {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    //?}
    //? if <26.2 {
    /*/^* 26.1 GUI rendering needs the fog uniform buffer the vanilla GUI renderer received this frame. ^/
    private static @Nullable GpuBufferSlice fogBuffer;

    public static void setGuiFogBuffer(GpuBufferSlice buffer) {
        fogBuffer = buffer;
    }
    *///?}

    private RenderCompat() {
    }

    public static RenderTarget mainRenderTarget() {
        //? if >=26.2 {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
        //?} else
        /*return Minecraft.getInstance().getMainRenderTarget();*/
    }

    public static GuiRenderState mainGuiRenderState() {
        //? if >=26.2 {
        return Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState;
        //?} else
        /*return Minecraft.getInstance().gameRenderer.getGameRenderState().guiRenderState;*/
    }

    /** A color + depth target for replaying GUI layers. */
    public static TextureTarget createLayerTarget(String label, int width, int height) {
        //? if >=26.3 {
        /*return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        *///?} elif >=26.2 {
        return new TextureTarget(label, width, height, true, GpuFormat.RGBA8_UNORM);
        //?} else
        /*return new TextureTarget(label, width, height, true);*/
    }

    /** A colour-only target (no depth buffer). */
    public static TextureTarget createColorTarget(String label, int width, int height) {
        //? if >=26.3 {
        /*return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, null);
        *///?} elif >=26.2 {
        return new TextureTarget(label, width, height, false, GpuFormat.RGBA8_UNORM);
        //?} else
        /*return new TextureTarget(label, width, height, false);*/
    }

    /** Copies a colour texture of the same size and format into the target. */
    public static void copyColor(GpuTexture source, RenderTarget target) {
        RenderSystem.getDevice().createCommandEncoder()
            .copyTextureToTexture(source, target.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
    }

    public static void clearDepth(RenderTarget target) {
        //? if >=26.2 {
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 0.0);
        //?} else
        /*RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 1.0);*/
    }

    /** Clears color to transparent black and depth to the value the GUI expects (reverse-Z since 26.2). */
    public static void clearTransparent(RenderTarget target) {
        //? if >=26.2 {
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(target.getColorTexture(), TRANSPARENT, target.getDepthTexture(), 0.0);
        //?} else {
        /*RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
        *///?}
    }

    /**
     * Draws a full-screen triangle into {@code output} with a pipeline using the {@code core/screenquad} vertex shader,
     * binding each texture view (sampled with nearest filtering) to the sampler of the same index in {@code samplers}.
     */
    public static void fullscreenPass(String label, RenderPipeline pipeline, GpuTextureView output, String[] samplers,
                                      GpuTextureView[] textures) {
        //? if >=26.3 {
        /*try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, output, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            for (int i = 0; i < samplers.length; i++) {
                pass.setUniform(samplers[i], textures[i], RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            pass.draw(3, 1, 0, 0);
        }
        *///?} elif >=26.2 {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, output, Optional.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            for (int i = 0; i < samplers.length; i++) {
                pass.bindTexture(samplers[i], textures[i], RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            pass.draw(3, 1, 0, 0);
        }
        //?} else {
        /*try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, output, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            for (int i = 0; i < samplers.length; i++) {
                pass.bindTexture(samplers[i], textures[i], RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            pass.draw(0, 3);
        }
        *///?}
    }

    /**
     * What {@code GuiRenderer.render()} does, minus the panorama, without going through that method (see
     * {@link GuiRendererInternals}). Keep in sync with the vanilla method when porting.
     */
    public static void renderGui(GuiRenderer renderer) {
        GuiRendererInternals gui = (GuiRendererInternals) renderer;
        gui.layercast$prepare();
        //? if >=26.2 {
        net.minecraft.client.renderer.StagedVertexBuffer vertexBuffer = gui.layercast$vertexBuffer();
        vertexBuffer.upload();
        //? if >=26.3
        /*RenderSystem.resizeAllAutoStorageIndexBuffers();*/
        gui.layercast$draw();
        vertexBuffer.endDraw();
        vertexBuffer.endFrame();
        //?} else {
        /*gui.layercast$draw(fogBuffer);
        gui.layercast$vertexBuffers().values().forEach(net.minecraft.client.renderer.MappableRingBuffer::rotate);
        gui.layercast$meshesToDraw().clear();
        *///?}
        gui.layercast$draws().clear();
        gui.layercast$renderState().reset();
        gui.layercast$setFirstDrawIndexAfterBlur(Integer.MAX_VALUE);
        gui.layercast$clearUnusedOversizedItemRenderers();
    }

    public static String backendName() {
        //? if >=26.2 {
        return RenderSystem.getDevice().getDeviceInfo().backendName();
        //?} else
        /*return RenderSystem.getDevice().getBackendName();*/
    }

    /** A mapped GPU buffer; must be closed on the render thread. */
    public interface Mapped extends AutoCloseable {
        ByteBuffer data();

        @Override
        void close();
    }

    public static Mapped mapForRead(GpuBuffer buffer) {
        //? if >=26.2 {
        GpuBufferSlice.MappedView view = buffer.map(true, false);
        //?} else
        /*GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false);*/
        return new Mapped() {
            @Override
            public ByteBuffer data() {
                return view.data();
            }

            @Override
            public void close() {
                view.close();
            }
        };
    }
}
