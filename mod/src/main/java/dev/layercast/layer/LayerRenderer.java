package dev.layercast.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.layercast.LayerCast;
import dev.layercast.compat.RenderCompat;
import dev.layercast.mixin.PictureInPictureRendererInvoker;
import dev.layercast.platform.LoaderPlatform;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;


/**
 * Replays the elements recorded by {@link LayerCapture} into an off-screen target, one GUI layer at a time.
 * <p>
 * Each GUI layer owns a private {@link GuiRenderer} (vertex buffer pool and item atlas are per renderer and must
 * only be used once per frame), created lazily the first time the layer is requested. All layers draw into one
 * shared transparent {@link TextureTarget}: the GPU executes "clear → draw layer → copy to shared surface"
 * strictly in order, so a single target suffices regardless of how many layers are active.
 * <p>
 * Picture-in-picture elements (entity previews, minimaps, ...) and items are not rendered again: a layer blits the
 * texture (or item atlas slot) the vanilla renderer produced for the very same element earlier in the frame. That costs nothing, and it works
 * with mod renderers that only render an element once (Xaero's minimap marks its states as prepared).
 */
public final class LayerRenderer {
    private static final long IDLE_CLOSE_NS = 30_000_000_000L;

    /** Target the currently rendering layer {@link GuiRenderer} must draw into, {@code null} for vanilla. */
    private static @Nullable RenderTarget redirectTarget;
    /** The redirect target already holds an opaque picture (the game layer's world), so vanilla blending works. */
    private static boolean redirectOpaque;
    /** Renderer that drew each picture-in-picture element of the vanilla GUI in a frame whose layers are recorded. */
    private static final Map<PictureInPictureRenderState, PictureInPictureRenderer<?>> PICTURE_IN_PICTURE = new IdentityHashMap<>();
    /** Item atlas slot the vanilla GUI used for each item of the frame. */
    private static final Map<GuiItemRenderState, GuiItemAtlas.SlotView> ITEM_SLOTS = new IdentityHashMap<>();
    /** Oversized items are drawn by picture-in-picture renderers with a per-frame state wrapping the item. */
    private static final Map<GuiItemRenderState, OversizedItemRenderState> OVERSIZED = new IdentityHashMap<>();
    private static boolean rememberPictureInPicture;

    private final GuiRenderState[] states = new GuiRenderState[Layers.MAX_LAYERS];
    private final GuiRenderer[] renderers = new GuiRenderer[Layers.MAX_LAYERS];
    private final long[] lastUsed = new long[Layers.MAX_LAYERS];
    private final GuiRenderState[] recording = new GuiRenderState[Layers.MAX_LAYERS];
    private @Nullable TextureTarget target;
    /** The game layer's own target: a copy of the world, the unsplit GUI is drawn over it. */
    private @Nullable TextureTarget gameTarget;
    private boolean gamePrepared;

    public static @Nullable RenderTarget redirectTarget() {
        return redirectTarget;
    }

    /** Whether the current layer is drawn over transparent black (elements with special blending need replacing). */
    public static boolean redirectTransparent() {
        return redirectTarget != null && !redirectOpaque;
    }

    /** A picture-in-picture renderer added a blit of its texture for {@code state} to {@code guiRenderState}. */
    public static void onPictureInPictureBlit(PictureInPictureRenderer<?> renderer, PictureInPictureRenderState state, GuiRenderState guiRenderState) {
        if (rememberPictureInPicture && redirectTarget == null && guiRenderState == RenderCompat.mainGuiRenderState()) {
            PICTURE_IN_PICTURE.put(state, renderer);
            if (state instanceof OversizedItemRenderState oversized) {
                OVERSIZED.put(oversized.guiItemRenderState(), oversized);
            }
        }
    }

    public static void onItemBlit(GuiItemRenderState item, GuiItemAtlas.SlotView slot) {
        if (rememberPictureInPicture) {
            ITEM_SLOTS.put(item, slot);
        }
    }

    public static GuiItemAtlas.@Nullable SlotView vanillaItemSlot(GuiItemRenderState item) {
        return ITEM_SLOTS.get(item);
    }

    /** Adds the texture vanilla drew an oversized item with this frame to a layer. */
    public static void blitOversizedItem(GuiItemRenderState item, GuiRenderState layerState) {
        OversizedItemRenderState oversized = OVERSIZED.get(item);
        if (oversized != null) {
            blitPictureInPicture(oversized, layerState);
        }
    }

    /**
     * Adds the texture the vanilla GUI used for {@code state} this frame to a layer. Elements vanilla did not draw
     * (no renderer for them) are skipped, as they are invisible in the game too.
     */
    public static void blitPictureInPicture(PictureInPictureRenderState state, GuiRenderState layerState) {
        PictureInPictureRenderer<?> renderer = PICTURE_IN_PICTURE.get(state);
        if (renderer != null) {
            ((PictureInPictureRendererInvoker) renderer).layercast$blitTexture(state, layerState);
        }
    }

    /** Render states that should receive elements this frame; entries for idle layers are {@code null}. */
    public GuiRenderState[] prepareRecording(boolean[] due) {
        PICTURE_IN_PICTURE.clear();
        ITEM_SLOTS.clear();
        OVERSIZED.clear();
        rememberPictureInPicture = true;
        for (Layer layer : Layers.all()) {
            int i = layer.index();
            if (due[i]) {
                if (this.states[i] == null) {
                    this.states[i] = new GuiRenderState();
                }
                this.recording[i] = this.states[i];
            } else {
                this.recording[i] = null;
            }
        }
        return this.recording;
    }

    public boolean isRecording(Layer layer) {
        return this.recording[layer.index()] != null;
    }

    /** Drops a recorded layer without drawing it. */
    public void discard(Layer layer) {
        this.recording[layer.index()] = null;
    }

    /**
     * Draws a recorded GUI layer into the shared off-screen target and returns its color texture.
     * Must run after the vanilla GUI was rendered for the frame.
     */
    public @Nullable GpuTexture render(Layer layer, int width, int height) {
        int i = layer.index();
        GuiRenderState state = this.recording[i];
        if (state == null) {
            throw new IllegalStateException("Layer " + layer.id() + " was not recorded this frame");
        }
        boolean game = layer.kind() == Layer.Kind.GAME;
        if (game && (!this.gamePrepared || this.gameTarget == null)) {
            this.recording[i] = null;
            return null; // the world was not captured this frame
        }
        GuiRenderer renderer = this.renderers[i];
        if (renderer == null) {
            renderer = this.createRenderer(state);
            this.renderers[i] = renderer;
        }
        TextureTarget target;
        if (game) {
            target = this.gameTarget;
        } else {
            target = this.ensureTarget(width, height);
            RenderCompat.clearTransparent(target);
        }
        redirectTarget = target;
        redirectOpaque = game;
        try {
            RenderCompat.renderGui(renderer);
            renderer.endFrame();
        } finally {
            redirectTarget = null;
            redirectOpaque = false;
            this.recording[i] = null;
        }
        this.lastUsed[i] = System.nanoTime();
        return target.getColorTexture();
    }

    /**
     * Copies the finished world (the main target before the GUI is drawn) into the game layer's target, which is
     * returned.
     */
    public RenderTarget prepareGame(RenderTarget main) {
        if (this.gameTarget == null) {
            this.gameTarget = RenderCompat.createLayerTarget("LayerCast game", main.width, main.height);
        } else if (this.gameTarget.width != main.width || this.gameTarget.height != main.height) {
            this.gameTarget.resize(main.width, main.height);
        }
        RenderCompat.copyColor(main.getColorTexture(), this.gameTarget);
        RenderCompat.clearDepth(this.gameTarget);
        this.gamePrepared = true;
        return this.gameTarget;
    }

    private GuiRenderer createRenderer(GuiRenderState state) {
        return LoaderPlatform.Holder.get().createGuiRenderer(state);
    }

    private TextureTarget ensureTarget(int width, int height) {
        if (this.target == null) {
            this.target = RenderCompat.createLayerTarget("LayerCast layer", width, height);
        } else if (this.target.width != width || this.target.height != height) {
            this.target.resize(width, height);
        }
        return this.target;
    }

    /** All layers of the frame are rendered. */
    public void finishFrame() {
        this.gamePrepared = false;
        rememberPictureInPicture = false;
        PICTURE_IN_PICTURE.clear();
        ITEM_SLOTS.clear();
        OVERSIZED.clear();
    }

    /** Drops the state of layers whose renderers were not used for a while. */
    public void collectIdle() {
        long now = System.nanoTime();
        for (int i = 0; i < this.renderers.length; i++) {
            if (this.renderers[i] != null && now - this.lastUsed[i] > IDLE_CLOSE_NS) {
                LayerCast.LOGGER.debug("Closing idle layer renderer {}", i);
                this.renderers[i].close();
                this.renderers[i] = null;
                this.states[i] = null;
            }
        }
    }

    public void close() {
        this.finishFrame();
        for (int i = 0; i < this.renderers.length; i++) {
            if (this.renderers[i] != null) {
                this.renderers[i].close();
                this.renderers[i] = null;
            }
            this.states[i] = null;
        }
        if (this.target != null) {
            this.target.destroyBuffers();
            this.target = null;
        }
        if (this.gameTarget != null) {
            this.gameTarget.destroyBuffers();
            this.gameTarget = null;
        }
    }
}
