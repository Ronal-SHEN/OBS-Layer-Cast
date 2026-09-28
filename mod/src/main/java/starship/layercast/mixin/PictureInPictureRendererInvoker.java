package starship.layercast.mixin;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PictureInPictureRenderer.class)
public interface PictureInPictureRendererInvoker {
    /** Adds a blit of the renderer's current texture for {@code state} to {@code guiRenderState}. */
    @Invoker("blitTexture")
    void layercast$blitTexture(PictureInPictureRenderState state, GuiRenderState guiRenderState);
}
