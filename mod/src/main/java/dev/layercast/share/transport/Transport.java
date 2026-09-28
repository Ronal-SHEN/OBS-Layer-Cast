package dev.layercast.share.transport;

import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Moves finished layer frames from a Minecraft-owned GPU texture into memory that OBS can read.
 * One instance serves one layer and owns its ring of shared surfaces. All methods run on the render thread.
 */
public interface Transport extends AutoCloseable {
    /** Protocol transport id ({@code Protocol.TRANSPORT_*}). */
    int protocolId();

    /** Protocol pixel format of the shared surfaces ({@code Protocol.FORMAT_*}). */
    int format();

    /** Whether row 0 of the shared image is the bottom row of the picture. */
    boolean flipY();

    /** Called before {@link #allocate} with the identity the next configuration will be published under. */
    default void prepare(String channel, int layerIndex, int nextGeneration) {
    }

    /**
     * (Re)creates {@code slots} shared surfaces of the given size and returns their protocol handles.
     * Previously allocated surfaces are released.
     */
    long[] allocate(int width, int height, int slots);

    int width();

    int height();

    /**
     * Records a GPU copy of {@code source} (same size as allocated) into the given slot. Must not block.
     * Completion is tracked by the caller with a {@code GpuFence} created right after this call.
     */
    void copy(GpuTexture source, int slot);

    /**
     * Announces, before any layer of the frame is drawn, that {@code slot} will be copied into this frame, so
     * transports that have to synchronise with another API can do that once for all layers of the frame.
     * {@link #endFrame} is called afterwards even if the copy does not happen.
     */
    default void reserve(int slot) {
    }

    /**
     * Called (possibly repeatedly, once per frame) after the fence recorded after {@link #copy} has signalled.
     * The slot is published once this returns true. CPU transports use it to move read-back data into shared memory.
     */
    default boolean isSlotReady(int slot) {
        return true;
    }

    /** Whether the slot must not be written yet, independently of pending fences. */
    default boolean isSlotBusy(int slot) {
        return false;
    }

    /** Called once per frame after all copies of this frame were recorded. */
    default void endFrame() {
    }

    @Override
    void close();
}
