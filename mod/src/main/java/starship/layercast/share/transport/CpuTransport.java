package starship.layercast.share.transport;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import starship.layercast.LayerCast;
import starship.layercast.compat.RenderCompat;
import starship.layercast.share.Protocol;
import starship.layercast.share.ffm.Native;
import starship.layercast.share.ffm.SharedMemory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Universal fallback: frames go GPU → CPU → shared memory → OBS upload. Used when no GPU sharing path exists
 * (e.g. Linux, or OBS rendering on a different GPU than Minecraft).
 * <p>
 * Still never stalls the render thread: the read-back is Minecraft's asynchronous texture-to-buffer copy, the
 * buffer is only mapped after its fence signalled, and the (large) memcpy into shared memory runs on a worker
 * thread while the mapping stays open. The slot is published once the worker finished.
 */
public final class CpuTransport implements Transport {
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final ExecutorService COPIER = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "LayerCast frame copier");
        thread.setDaemon(true);
        return thread;
    });

    private String channel = "default";
    private int layerIndex;
    private int generation;
    private SharedMemory memory;
    private GpuBuffer[] buffers = new GpuBuffer[0];
    private RenderCompat.Mapped[] views = new RenderCompat.Mapped[0];
    private Future<?>[] copies = new Future<?>[0];
    private int width;
    private int height;

    @Override
    public int protocolId() {
        return Protocol.TRANSPORT_SHM;
    }

    @Override
    public int format() {
        return Protocol.FORMAT_RGBA8;
    }

    @Override
    public boolean flipY() {
        return true;
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return this.height;
    }

    @Override
    public void prepare(String channel, int layerIndex, int nextGeneration) {
        this.channel = channel;
        this.layerIndex = layerIndex;
        this.generation = nextGeneration;
    }

    @Override
    public long[] allocate(int width, int height, int slots) {
        this.release();
        long frameBytes = (long) width * height * 4;
        String name = Protocol.shmFrameName(this.channel, this.layerIndex, this.generation, Native.OS == Native.Os.WINDOWS);
        this.memory = SharedMemory.createOrOpen(name, Protocol.SHM_DATA_OFFSET + frameBytes * slots);
        MemorySegment seg = this.memory.segment();
        seg.set(ValueLayout.JAVA_INT, Protocol.S_WIDTH, width);
        seg.set(ValueLayout.JAVA_INT, Protocol.S_HEIGHT, height);
        seg.set(ValueLayout.JAVA_INT, Protocol.S_STRIDE, width * 4);
        for (int i = 0; i < Protocol.MAX_SLOTS; i++) {
            seg.set(ValueLayout.JAVA_LONG, Protocol.S_FRAME_SEQ + i * 8L, 0L);
        }
        seg.set(ValueLayout.JAVA_INT, Protocol.S_MAGIC, Protocol.MAGIC);

        this.buffers = new GpuBuffer[slots];
        this.views = new RenderCompat.Mapped[slots];
        this.copies = new Future<?>[slots];
        for (int i = 0; i < slots; i++) {
            int slot = i;
            this.buffers[i] = RenderSystem.getDevice().createBuffer(() -> "LayerCast readback " + this.layerIndex + "/" + slot,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, frameBytes);
        }
        this.width = width;
        this.height = height;
        return new long[slots]; // no handles: consumers derive the block name from channel/layer/generation
    }

    @Override
    public boolean isSlotBusy(int slot) {
        return this.views[slot] != null;
    }

    @Override
    public void copy(GpuTexture source, int slot) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(source, this.buffers[slot], 0L, () -> {
        }, 0);
    }

    @Override
    public boolean isSlotReady(int slot) {
        if (this.views[slot] == null) {
            // The GPU copy finished (its fence signalled): mapping no longer blocks. Hand the memcpy to a worker.
            RenderCompat.Mapped view = RenderCompat.mapForRead(this.buffers[slot]);
            this.views[slot] = view;
            MemorySegment shm = this.memory.segment();
            long frameBytes = (long) this.width * this.height * 4;
            long seqOffset = Protocol.S_FRAME_SEQ + slot * 8L;
            long dataOffset = Protocol.SHM_DATA_OFFSET + frameBytes * slot;
            this.copies[slot] = COPIER.submit(() -> {
                long seq = (long) LONG.getAcquire(shm, seqOffset);
                LONG.setRelease(shm, seqOffset, seq | 1L);
                MemorySegment.copy(MemorySegment.ofBuffer(view.data()), 0, shm, dataOffset, frameBytes);
                LONG.setRelease(shm, seqOffset, (seq | 1L) + 1L);
            });
            return false;
        }
        Future<?> copy = this.copies[slot];
        if (copy == null || !copy.isDone()) {
            return false;
        }
        this.views[slot].close();
        this.views[slot] = null;
        this.copies[slot] = null;
        try {
            copy.get();
        } catch (Exception e) {
            LayerCast.LOGGER.warn("Frame copy into shared memory failed", e);
            return false;
        }
        return true;
    }

    private void release() {
        for (int i = 0; i < this.views.length; i++) {
            if (this.copies[i] != null) {
                try {
                    this.copies[i].get();
                } catch (Exception ignored) {
                }
            }
            if (this.views[i] != null) {
                this.views[i].close();
            }
        }
        for (GpuBuffer buffer : this.buffers) {
            buffer.close();
        }
        this.buffers = new GpuBuffer[0];
        this.views = new RenderCompat.Mapped[0];
        this.copies = new Future<?>[0];
        if (this.memory != null) {
            this.memory.close(true);
            this.memory = null;
        }
    }

    @Override
    public void close() {
        this.release();
    }
}
