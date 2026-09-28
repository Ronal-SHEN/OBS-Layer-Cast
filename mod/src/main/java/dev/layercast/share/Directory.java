package dev.layercast.share;

import dev.layercast.layer.Layer;
import dev.layercast.share.ffm.Native;
import dev.layercast.share.ffm.SharedMemory;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static dev.layercast.share.Protocol.*;

/**
 * Producer view of the shared directory (see {@code layercast-protocol.h}).
 */
public final class Directory implements AutoCloseable {
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();

    private static final long SELF = ProcessHandle.current().pid();

    private final SharedMemory memory;
    private final MemorySegment seg;
    private final int[] generation = new int[MAX_LAYERS];

    private Directory(SharedMemory memory) {
        this.memory = memory;
        this.seg = memory.segment();
    }

    /**
     * @param available layers offered to OBS; the others are listed with {@code STATE_EMPTY} so their slot and id
     *                  stay reserved (a source pointing at a layer that is not split out simply shows nothing)
     */
    public static @Nullable Directory claim(String channel, int backend, String producerName, Iterable<Layer> layers,
                                            java.util.function.Predicate<Layer> available) {
        String name = directoryName(channel, Native.OS == Native.Os.WINDOWS);
        SharedMemory memory = SharedMemory.createOrOpen(name, DIRECTORY_SIZE);
        Directory directory = new Directory(memory);
        long owner = directory.producerPid();
        if (directory.valid() && owner != SELF && isOtherMinecraft(owner)) {
            memory.close(false); // another Minecraft shares on this channel
            return null;
        }
        directory.initialize(backend, producerName, layers, available);
        return directory;
    }

    /**
     * Whether a process could be another running Minecraft. A directory left behind by a crashed game (process gone,
     * or its PID reused by something that is not Java) can be taken over.
     */
    private static boolean isOtherMinecraft(long pid) {
        return ProcessHandle.of(pid)
            .filter(ProcessHandle::isAlive)
            .map(process -> process.info().command()
                .map(command -> command.toLowerCase(java.util.Locale.ROOT).contains("java"))
                .orElse(true))
            .orElse(false);
    }

    private boolean valid() {
        return (int) INT.getAcquire(this.seg, H_MAGIC) == MAGIC;
    }

    private long producerPid() {
        return (long) LONG.getAcquire(this.seg, H_PRODUCER_PID);
    }

    /**
     * Whether this process still owns the directory. Two games started at the same moment can both claim a free
     * channel; the one whose header got overwritten loses and must move to another channel.
     */
    public boolean owned() {
        return this.valid() && this.producerPid() == SELF;
    }

    private void initialize(int backend, String producerName, Iterable<Layer> layers, java.util.function.Predicate<Layer> available) {
        // Invalidate first so consumers never mix an old session's layers with the new header.
        INT.setRelease(this.seg, H_MAGIC, 0);
        int previousSeq = this.seg.get(ValueLayout.JAVA_INT, H_DIRECTORY_SEQ);
        this.seg.asSlice(HEADER_SIZE, (long) LAYER_STRIDE * MAX_LAYERS).fill((byte) 0);
        this.seg.asSlice(0, HEADER_SIZE).fill((byte) 0);

        this.seg.set(ValueLayout.JAVA_INT, H_VERSION, VERSION);
        this.seg.set(ValueLayout.JAVA_INT, H_HEADER_SIZE, HEADER_SIZE);
        this.seg.set(ValueLayout.JAVA_INT, H_LAYER_STRIDE, LAYER_STRIDE);
        this.seg.set(ValueLayout.JAVA_INT, H_MAX_LAYERS, MAX_LAYERS);
        this.seg.set(ValueLayout.JAVA_LONG, H_SESSION_ID, new SecureRandom().nextLong() | 1L);
        this.seg.set(ValueLayout.JAVA_LONG, H_PRODUCER_PID, SELF);
        this.seg.set(ValueLayout.JAVA_INT, H_BACKEND, backend);
        putString(H_PRODUCER_NAME, PRODUCER_NAME_LEN, producerName);

        int count = 0;
        for (Layer layer : layers) {
            this.writeLayer(layer, available.test(layer));
            count = Math.max(count, layer.index() + 1);
        }
        this.seg.set(ValueLayout.JAVA_INT, H_LAYER_COUNT, count);
        this.seg.set(ValueLayout.JAVA_INT, H_DIRECTORY_SEQ, previousSeq + 1);
        heartbeat();
        INT.setRelease(this.seg, H_MAGIC, MAGIC);
    }

    private void writeLayer(Layer layer, boolean available) {
        long base = layerBase(layer.index());
        putString(base + L_ID, ID_LEN, layer.id());
        putString(base + L_NAME, NAME_LEN, layer.name());
        this.seg.set(ValueLayout.JAVA_INT, base + L_CONSUMER_READING_SLOT, -1);
        // Start generations from a random-ish even value so consumers notice a new session even if
        // they somehow missed the session id change.
        this.generation[layer.index()] = (int) (System.nanoTime() & 0x7FFF_FFFEL);
        this.seg.set(ValueLayout.JAVA_INT, base + L_GENERATION, this.generation[layer.index()]);
        INT.setRelease(this.seg, base + L_STATE, available ? STATE_IDLE : STATE_EMPTY);
    }

    /** Adds a layer registered after the directory was created (a mod HUD seen for the first time). */
    public void addLayer(Layer layer, boolean available) {
        this.writeLayer(layer, available);
        int count = this.seg.get(ValueLayout.JAVA_INT, H_LAYER_COUNT);
        INT.setRelease(this.seg, H_LAYER_COUNT, Math.max(count, layer.index() + 1));
        INT.setRelease(this.seg, H_DIRECTORY_SEQ, this.seg.get(ValueLayout.JAVA_INT, H_DIRECTORY_SEQ) + 1);
    }

    private static long layerBase(int index) {
        return HEADER_SIZE + (long) index * LAYER_STRIDE;
    }

    private void putString(long offset, int capacity, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(bytes.length, capacity - 1);
        MemorySegment.copy(bytes, 0, this.seg, ValueLayout.JAVA_BYTE, offset, len);
        this.seg.set(ValueLayout.JAVA_BYTE, offset + len, (byte) 0);
    }

    public void heartbeat() {
        LONG.setRelease(this.seg, H_PRODUCER_HEARTBEAT, System.currentTimeMillis());
    }

    /** Last time any OBS source asked for this layer. */
    public long consumerHeartbeat(int layer) {
        return (long) LONG.getAcquire(this.seg, layerBase(layer) + L_CONSUMER_HEARTBEAT);
    }

    /** Last time any OBS source reading this layer ticked, shown or hidden (0 with plugins that predate the field). */
    public long consumerAttached(int layer) {
        return (long) LONG.getAcquire(this.seg, layerBase(layer) + L_CONSUMER_ATTACHED);
    }

    public int consumerReadingSlot(int layer) {
        return (int) INT.getAcquire(this.seg, layerBase(layer) + L_CONSUMER_READING_SLOT);
    }

    /** The OBS output frame rate reported by the consumer, or 0 if unknown. */
    public int consumerFps(int layer) {
        return (int) INT.getAcquire(this.seg, layerBase(layer) + L_CONSUMER_FPS);
    }

    /** Returns true if a consumer failed to open the handles of the current generation. */
    public boolean consumerFailed(int layer) {
        long base = layerBase(layer);
        int error = (int) INT.getAcquire(this.seg, base + L_CONSUMER_ERROR);
        int errorGeneration = (int) INT.getAcquire(this.seg, base + L_CONSUMER_ERROR_GENERATION);
        return error != CONSUMER_OK && errorGeneration == this.generation[layer];
    }

    /**
     * Publishes a new shared-surface configuration for a layer. Invalidates previously published frames.
     */
    public void configure(int layer, int state, int flags, int width, int height, int format, int transport, long[] handles, int fps) {
        long base = layerBase(layer);
        int gen = this.generation[layer];
        int writing = gen + 1; // odd: configuration being rewritten
        INT.setRelease(this.seg, base + L_GENERATION, writing);
        VarHandle.fullFence();
        this.seg.set(ValueLayout.JAVA_INT, base + L_STATE, state);
        this.seg.set(ValueLayout.JAVA_INT, base + L_FLAGS, flags);
        this.seg.set(ValueLayout.JAVA_INT, base + L_WIDTH, width);
        this.seg.set(ValueLayout.JAVA_INT, base + L_HEIGHT, height);
        this.seg.set(ValueLayout.JAVA_INT, base + L_FORMAT, format);
        this.seg.set(ValueLayout.JAVA_INT, base + L_TRANSPORT, transport);
        this.seg.set(ValueLayout.JAVA_INT, base + L_SLOT_COUNT, handles.length);
        for (int i = 0; i < MAX_SLOTS; i++) {
            this.seg.set(ValueLayout.JAVA_LONG, base + L_SLOT_HANDLES + i * 8L, i < handles.length ? handles[i] : 0L);
        }
        this.seg.set(ValueLayout.JAVA_LONG, base + L_PUBLISHED, 0L);
        this.seg.set(ValueLayout.JAVA_INT, base + L_TARGET_FPS, fps);
        VarHandle.fullFence();
        this.generation[layer] = writing + 1;
        INT.setRelease(this.seg, base + L_GENERATION, writing + 1);
    }

    public int generation(int layer) {
        return this.generation[layer];
    }

    /** The generation the next {@link #configure} call will publish. */
    public int nextGeneration(int layer) {
        return this.generation[layer] + 2;
    }

    public void setState(int layer, int state) {
        INT.setRelease(this.seg, layerBase(layer) + L_STATE, state);
    }

    /** The layer list OBS offers changed (a layer was split out or merged back). */
    public void layerListChanged() {
        INT.setRelease(this.seg, H_DIRECTORY_SEQ, this.seg.get(ValueLayout.JAVA_INT, H_DIRECTORY_SEQ) + 1);
    }

    public void publish(int layer, long frame, int slot) {
        long base = layerBase(layer);
        this.seg.set(ValueLayout.JAVA_LONG, base + L_PUBLISH_TIME, System.currentTimeMillis());
        LONG.setRelease(this.seg, base + L_PUBLISHED, packPublished(frame, slot));
    }

    public String name() {
        return this.memory.name();
    }

    /** Invalidates and removes the directory, unless another game took it over (then it is only unmapped). */
    @Override
    public void close() {
        boolean owned = this.owned();
        if (owned) {
            INT.setRelease(this.seg, H_MAGIC, 0);
            LONG.setRelease(this.seg, H_PRODUCER_HEARTBEAT, 0L);
        }
        this.memory.close(owned);
    }
}
