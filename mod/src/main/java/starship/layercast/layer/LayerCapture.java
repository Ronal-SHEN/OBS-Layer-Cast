package starship.layercast.layer;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Routes GUI elements into per-layer {@link GuiRenderState}s while the GUI is being extracted.
 * <p>
 * Every element is extracted exactly once by vanilla (or mod) code into the main render state. While recording,
 * {@code GuiRenderStateMixin} hands the very same (immutable) element object to exactly one layer:
 * <ul>
 *     <li>inside a vanilla component scope (hotbar, chat, F3, ...): that component's layer if the player split it
 *     out, otherwise the {@link Layers#GAME game} layer, which draws them over the world;</li>
 *     <li>elsewhere ("unclaimed": the HUD outside the components and everything drawn after the vanilla GUI):
 *     the layer of the mod drawing it ({@link ModAttribution}) if that mod's HUD is split out, otherwise the game
 *     layer.</li>
 * </ul>
 * The game layer plus the split-out layers therefore always add up to the complete picture. Split-out layers that
 * are not rendered this frame drop their elements (they must not fall back into the game layer). All methods run on
 * the render thread.
 */
public final class LayerCapture {
    private static final int MAX_DEPTH = 64;
    /** Scope marker: GUI drawn outside the vanilla components, i.e. most likely by another mod. */
    private static final int UNCLAIMED = -1;

    private static final GuiRenderState[] STATES = new GuiRenderState[Layers.MAX_LAYERS];
    private static final boolean[] SPLIT = new boolean[Layers.MAX_LAYERS];
    /** By layer index: an element was routed to the layer in the current (or last) recorded frame. */
    private static final boolean[] CONTENT = new boolean[Layers.MAX_LAYERS];
    private static final int[] SCOPE_STACK = new int[MAX_DEPTH];

    private static Function<String, @Nullable Layer> modLayers = modId -> null;
    private static @Nullable GuiRenderState mainState;
    private static boolean recording;
    private static boolean attributeMods;
    private static int depth;
    private static boolean unclaimed;
    private static int scopedIndex;

    private LayerCapture() {
    }

    /** Resolves (and registers on first sight) the layer of a mod's HUD. */
    public static void setModLayers(Function<String, @Nullable Layer> resolver) {
        modLayers = resolver;
    }

    /**
     * Starts routing for one frame.
     *
     * @param main          the vanilla render state that receives the extracted GUI
     * @param layerStates   render states of the layers rendered this frame, by layer index ({@code null} = not rendered)
     * @param split         by layer index: whether the layer is split out of the game layer
     * @param attributeMods find the mod behind unclaimed elements (needed when a mod layer is split); without it
     *                      they all go to the game layer
     * @param discovery     attribute unclaimed elements even if no layer receives them, to find mod HUDs
     */
    public static void beginRecording(GuiRenderState main, @Nullable GuiRenderState[] layerStates, boolean[] split,
                                      boolean attributeMods, boolean discovery) {
        mainState = main;
        for (int i = 0; i < STATES.length; i++) {
            GuiRenderState state = i < layerStates.length ? layerStates[i] : null;
            STATES[i] = state;
            if (state != null) {
                state.reset();
            }
            SPLIT[i] = i < split.length && split[i];
            CONTENT[i] = false;
        }
        // Unclaimed elements can only end up in the game layer or a mod layer; when none of them is rendered this
        // frame (layers are spread over frames), there is nobody to route them to and no reason to attribute them.
        boolean unclaimedRendered = STATES[Layers.GAME.index()] != null;
        for (Layer layer : Layers.all()) {
            unclaimedRendered |= layer.kind() == Layer.Kind.MOD && STATES[layer.index()] != null;
        }
        LayerCapture.attributeMods = (attributeMods && unclaimedRendered) || discovery;
        depth = 0;
        recording = true;
        recompute();
    }

    public static void endRecording() {
        recording = false;
        mainState = null;
        depth = 0;
        java.util.Arrays.fill(STATES, null);
    }

    public static boolean isRecording(GuiRenderState state) {
        return recording && state == mainState;
    }

    /** Opens the scope of a vanilla component; must be balanced by {@link #exit()} (or dropped at the end of the frame). */
    public static void enter(Layer layer) {
        push(layer.index());
    }

    /** Opens a scope whose elements are attributed to the mod drawing them. */
    public static void enterUnclaimed() {
        push(UNCLAIMED);
    }

    /** Opens the scope of a mod's HUD, when the loader tells which mod is drawing (NeoForge GUI layers). */
    public static void enterMod(String modId) {
        if (!recording) {
            return;
        }
        Layer layer = modLayers.apply(modId);
        push(layer != null ? layer.index() : Layers.GAME.index());
    }

    private static void push(int scope) {
        if (!recording) {
            return;
        }
        if (depth < MAX_DEPTH) {
            SCOPE_STACK[depth] = scope;
        }
        depth++;
        recompute();
    }

    public static void exit() {
        if (!recording || depth == 0) {
            return;
        }
        depth--;
        recompute();
    }

    private static void recompute() {
        int top = depth == 0 ? UNCLAIMED : SCOPE_STACK[Math.min(depth, MAX_DEPTH) - 1];
        unclaimed = top == UNCLAIMED;
        scopedIndex = unclaimed ? Layers.GAME.index() : targetOf(top);
    }

    private static int targetOf(int layerIndex) {
        return SPLIT[layerIndex] ? layerIndex : Layers.GAME.index();
    }

    /** The layer render state the element being added right now belongs to, or {@code null} to drop it. */
    public static @Nullable GuiRenderState target() {
        int index = scopedIndex;
        if (unclaimed && attributeMods) {
            String mod = ModAttribution.currentOwner();
            if (mod != null) {
                Layer layer = modLayers.apply(mod);
                if (layer != null) {
                    index = targetOf(layer.index());
                }
            }
        }
        GuiRenderState state = STATES[index];
        if (state != null) {
            CONTENT[index] = true;
        }
        return state;
    }

    /** Whether any element was routed to the layer in the last recorded frame. */
    public static boolean hasContent(Layer layer) {
        return CONTENT[layer.index()];
    }

    /** Render states of every layer recorded this frame (for stratum changes, which apply to all of them). */
    public static void forEachRecording(java.util.function.Consumer<GuiRenderState> action) {
        for (GuiRenderState state : STATES) {
            if (state != null) {
                action.accept(state);
            }
        }
    }
}
