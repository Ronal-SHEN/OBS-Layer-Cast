package starship.layercast.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
//? if <26.3
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import starship.layercast.compat.RenderCompat;
import org.jspecify.annotations.Nullable;

/**
 * The name tags above entities, split out of the world picture into a layer of their own.
 * <p>
 * Unlike the GUI, name tags are drawn by the level renderer, in the middle of the world: translucent terrain,
 * clouds, weather and the first-person hand come after them. The player's picture must not change, so vanilla still
 * draws them into the main target, and the layer is produced next to it:
 * <ul>
 *     <li>Right before the first name tag is drawn, the main target's colour is copied ({@code world}: the world
 *     without name tags) and its depth is copied into the layer's target.</li>
 *     <li>Every group of name tags vanilla draws is drawn a second time into the (transparent) layer, testing and
 *     writing the copied depth, so the tags are hidden and seen through walls exactly as in the game.</li>
 *     <li>After the world and the hand are drawn, the depth buffer only holds the hand (it is cleared before the
 *     hand), and is copied into the layer's depth.</li>
 *     <li>Before the GUI, the name tags are taken out of the game layer (a copy of the finished world) wherever the
 *     layer has content and the hand does not cover it, and the layer is erased where the hand covers it. Where the
 *     tags are translucent (background plate, see-through text), the finished world is "un-blended" with the layer
 *     ({@code (frame - tags) / (1 - alpha)}); under opaque text, where nothing of the world is left, {@code world}
 *     is used.</li>
 * </ul>
 * The game layer plus the name tag layer then add up to the picture, except under opaque text that something drawn
 * later (water, glass, particles, rain) lies in front of. Where such things lie over or behind a translucent plate,
 * the game layer keeps them, slightly stronger than in the game. All methods run on the render thread.
 */
public final class NameTagLayer {
    /** The layer (or the game layer, which must leave the tags out) is captured this frame. */
    private static boolean active;
    private static boolean inLevel;
    /** Name tags were drawn this frame. */
    private static boolean drawn;
    /** {@link #finish} ran this frame: the layer's content is complete. */
    private static boolean finished;
    /** Colour: the name tags. Depth: the world's while the tags are drawn, then the hand's. */
    private static @Nullable TextureTarget target;
    /** The world right before the name tags were drawn. */
    private static @Nullable TextureTarget world;

    private NameTagLayer() {
    }

    /** Decides, before the frame is rendered, whether the name tags are captured. */
    public static void beginFrame(boolean capture) {
        active = capture;
        drawn = false;
        finished = false;
    }

    public static boolean active() {
        return active;
    }

    /** Whether name tags drawn right now go into the layer (only those of the level, not of GUI entity previews). */
    public static boolean capturing() {
        return active && inLevel;
    }

    public static void levelStart() {
        inLevel = true;
    }

    /** The world and the hand are drawn: keep the depth of the hand, which is all the depth buffer holds now. */
    public static void levelEnd(RenderTarget main) {
        inLevel = false;
        if (active && drawn && target != null) {
            target.copyDepthFrom(main);
        }
    }

    /** Vanilla is about to draw a group of name tags into the main target. */
    public static void beforeVanilla(RenderTarget main) {
        if (drawn) {
            return;
        }
        drawn = true;
        TextureTarget layer = ensure(main.width, main.height);
        RenderCompat.clearTransparent(layer);
        layer.copyDepthFrom(main);
        RenderCompat.copyColor(main.getColorTexture(), world);
    }

    /** Draws a group of name tags vanilla just drew into the main target again, into the layer. */
    public static void drawIntoLayer(Runnable draw) {
        TextureTarget layer = target;
        if (layer == null) {
            return;
        }
        //? if <26.3 {
        GpuTextureView color = RenderSystem.outputColorTextureOverride;
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = layer.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = layer.getDepthTextureView();
        try {
            draw.run();
        } finally {
            RenderSystem.outputColorTextureOverride = color;
            RenderSystem.outputDepthTextureOverride = depth;
        }
        //?} else
        /*throw new UnsupportedOperationException("No name tag layer on this Minecraft version");*/
    }

    /**
     * Before the GUI is drawn: takes the name tags out of the game layer (when it is captured, {@code game} holds a
     * copy of the finished world) and erases the parts of the layer the hand covers.
     */
    public static void finish(RenderTarget main, @Nullable RenderTarget game) {
        if (!active || finished) {
            return;
        }
        finished = true;
        TextureTarget layer = ensure(main.width, main.height);
        if (!drawn) {
            RenderCompat.clearTransparent(layer);
            return;
        }
        if (game != null) {
            RenderCompat.fullscreenPass("LayerCast name tags out of the game layer", LayerPipelines.NAME_TAG_GAME,
                game.getColorTextureView(), LayerPipelines.NAME_TAG_GAME_SAMPLERS,
                new GpuTextureView[]{layer.getColorTextureView(), main.getColorTextureView(), world.getColorTextureView(),
                    layer.getDepthTextureView()});
        }
        RenderCompat.fullscreenPass("LayerCast name tags under the hand", LayerPipelines.NAME_TAG_MASK,
            layer.getColorTextureView(), LayerPipelines.NAME_TAG_MASK_SAMPLERS,
            new GpuTextureView[]{layer.getDepthTextureView()});
    }

    /** Whether any name tag was drawn this frame. */
    public static boolean hasContent() {
        return drawn;
    }

    /** The finished layer of this frame, {@code null} if it was not captured. */
    public static @Nullable GpuTexture texture() {
        return active && finished && target != null ? target.getColorTexture() : null;
    }

    private static TextureTarget ensure(int width, int height) {
        if (target == null) {
            target = RenderCompat.createLayerTarget("LayerCast name tags", width, height);
            world = RenderCompat.createColorTarget("LayerCast world before name tags", width, height);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
            world.resize(width, height);
        }
        return target;
    }

    public static void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        if (world != null) {
            world.destroyBuffers();
            world = null;
        }
        active = false;
    }
}
