package dev.layercast.share;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.layercast.LayerCast;
import dev.layercast.LayerProfiler;
import dev.layercast.compat.RenderCompat;
import dev.layercast.config.LayerCastSettings;
import dev.layercast.layer.Layer;
import dev.layercast.layer.Layers;
import dev.layercast.share.ffm.Native;
import dev.layercast.share.transport.CpuTransport;
import dev.layercast.share.transport.Transport;
import dev.layercast.share.transport.Transports;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Owns the shared directory and one {@link Channel} per layer: decides which layers are due this frame,
 * copies finished layer textures into the transport ring and publishes slots once their GPU fence signalled.
 * <p>
 * Nothing here ever waits on the GPU: fences are polled with a zero timeout at the start of the next frame.
 * All methods run on the render thread.
 */
public final class SharingService {
    /** A layer nobody watched for this long gives its shared surfaces back. */
    private static final long RELEASE_AFTER_MS = 10_000;
    /** After a transport failure the layer is not retried for this long. */
    private static final long RETRY_AFTER_MS = 5_000;

    private final Channel[] channels = new Channel[Layers.MAX_LAYERS];
    private Directory directory;
    private String channelName;
    /** The configured channel the current directory was claimed for. */
    private String configuredChannel;
    private static final int MAX_CHANNEL_SUFFIX = 9;
    private static boolean noGpuLogged;
    private boolean channelNotice;
    private int backend = Protocol.BACKEND_UNKNOWN;
    private long lastDirectoryAttempt;
    private final boolean[] due = new boolean[Layers.MAX_LAYERS];
    private boolean anyDue;

    private int knownLayers;

    public SharingService() {
        this.syncLayers();
    }

    /** Creates channels (and directory entries) for layers registered since the last frame. */
    private void syncLayers() {
        for (int i = this.knownLayers; i < Layers.count(); i++) {
            Layer layer = Layers.all().get(i);
            Channel channel = new Channel(layer);
            channel.available = LayerCastSettings.available(layer);
            this.channels[i] = channel;
            if (this.directory != null) {
                this.directory.addLayer(layer, channel.available);
            }
        }
        this.knownLayers = Layers.count();
    }

    /**
     * Called once per frame before the GUI is extracted. Publishes completed frames and decides which layers
     * will be captured during this frame.
     */
    public void beginFrame() {
        this.beginFrame(false);
    }

    /**
     * @param captureAll ignore the frame pacing and capture every offered layer this frame, watched or not
     *                   (the layer export, which must contain all layers of the same frame even without OBS)
     */
    public void beginFrame(boolean captureAll) {
        this.anyDue = false;
        java.util.Arrays.fill(this.due, false);
        if (!LayerCastSettings.enabled()) {
            if (this.directory != null) {
                this.shutdown();
            }
            return;
        }
        this.syncLayers();
        if (!this.ensureDirectory()) {
            return;
        }
        if (!this.directory.owned()) {
            // Another game claimed the same channel at the same moment and won: leave it to that game.
            LayerCast.LOGGER.warn("Channel '{}' was taken over by another Minecraft; switching to another channel", this.channelName);
            this.shutdown();
            this.lastDirectoryAttempt = 0L;
            return;
        }
        this.directory.heartbeat();

        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();

        boolean anyOverdue = false;
        for (Channel channel : this.channels) {
            if (channel == null) {
                continue;
            }
            channel.pollFences();
            boolean available = LayerCastSettings.available(channel.layer);
            if (available != channel.available) {
                // Split out / merged back: offer or withdraw the layer in OBS right away.
                channel.available = available;
                if (available) {
                    this.directory.setState(channel.layer.index(), Protocol.STATE_IDLE);
                } else {
                    channel.release(Protocol.STATE_EMPTY);
                }
                this.directory.layerListChanged();
            }
            boolean watched = nowMs - this.directory.consumerHeartbeat(channel.layer.index()) < Protocol.CONSUMER_TIMEOUT_MS;
            boolean wanted = available && (watched || captureAll || LayerCastSettings.layerForced(channel.layer));
            if (wanted) {
                channel.lastWantedMs = nowMs;
            } else if (channel.transport != null && nowMs - channel.lastWantedMs > RELEASE_AFTER_MS) {
                channel.release(channel.idleState());
            }
            channel.overdueNs = Long.MIN_VALUE;
            if (!wanted || nowMs < channel.retryAtMs) {
                continue;
            }
            int fps = LayerCastSettings.maxFps();
            int consumerFps = this.directory.consumerFps(channel.layer.index());
            if (consumerFps > 0) {
                fps = Math.min(fps, consumerFps);
            }
            channel.fps = fps;
            long interval = 1_000_000_000L / Math.max(1, fps);
            // Allow 1 ms of jitter so a 60 fps game does not alternate between hitting and missing a 60 fps target.
            long overdue = nowNs - channel.lastCaptureNs - (interval - 1_000_000L);
            channel.overdueNs = captureAll ? 0L : overdue;
            // Layers that are almost due join a capture anyway (see below).
            channel.joinNs = -interval / 4;
            anyOverdue |= channel.overdueNs >= 0;
        }

        // All due layers are captured in the same frame: most of the cost (routing the GUI extraction, synchronising
        // with the shared surfaces) is paid once per frame whatever the number of layers, and OBS gets every layer
        // from the same game frame. Layers that are nearly due join in, so layers of the same rate stay in step.
        if (!anyOverdue) {
            return;
        }
        for (Channel channel : this.channels) {
            if (channel != null && channel.overdueNs != Long.MIN_VALUE && channel.overdueNs >= channel.joinNs) {
                this.due[channel.layer.index()] = true;
                this.anyDue = true;
            }
        }
    }

    public boolean isDue(Layer layer) {
        return this.due[layer.index()];
    }

    /**
     * A due layer that has nothing on it this frame, while the frame OBS shows is empty too, needs no new frame:
     * the capture is skipped and counts as done. Most split-out parts (titles, action bar, tab list, ...) are empty
     * most of the time.
     */
    public boolean skipEmpty(Layer layer) {
        Channel channel = this.channels[layer.index()];
        if (channel == null || !this.due[layer.index()] || !channel.lastEmpty || channel.transport == null) {
            return false;
        }
        this.due[layer.index()] = false;
        channel.lastCaptureNs = System.nanoTime();
        channel.skipped++;
        return true;
    }

    public boolean anyDue() {
        return this.anyDue;
    }

    /**
     * Records a copy of {@code source} into the next free slot of the layer. The texture must stay valid until the
     * copy was recorded (GPU ordering takes care of the rest).
     */
    public void submit(Layer layer, GpuTexture source, boolean empty) {
        Channel channel = this.channels[layer.index()];
        if (channel == null || this.directory == null || !this.due[layer.index()]) {
            return;
        }
        try {
            channel.submit(source, empty);
        } catch (Throwable t) {
            channel.logError("Sharing failed (retrying in " + RETRY_AFTER_MS / 1000 + " s)", t);
            channel.release(Protocol.STATE_ERROR);
            channel.retryAtMs = System.currentTimeMillis() + RETRY_AFTER_MS;
        }
    }

    /**
     * Before the layers of the frame are drawn: picks the slot every due layer will be copied into (layers are
     * {@code width} x {@code height}), so the transports can prepare all of them at once.
     */
    public void reserveSlots(int width, int height) {
        if (this.directory == null || !this.anyDue) {
            return;
        }
        for (Channel channel : this.channels) {
            if (channel != null && this.due[channel.layer.index()]) {
                try {
                    channel.reserve(width, height);
                } catch (Throwable t) {
                    channel.logError("Preparing the shared surface failed (retrying in " + RETRY_AFTER_MS / 1000 + " s)", t);
                    channel.release(Protocol.STATE_ERROR);
                    channel.retryAtMs = System.currentTimeMillis() + RETRY_AFTER_MS;
                }
            }
        }
    }

    /** Called after all layers of this frame were submitted. */
    public void endFrame() {
        for (Channel channel : this.channels) {
            if (channel != null && channel.transport != null && channel.submittedThisFrame) {
                channel.submittedThisFrame = false;
                channel.reservedSlot = -1;
                try {
                    channel.transport.endFrame();
                } catch (Throwable t) {
                    LayerCast.LOGGER.error("Transport endFrame failed for '{}'", channel.layer.id(), t);
                }
            }
        }
    }

    private boolean ensureDirectory() {
        String wantedChannel = Protocol.sanitizeChannel(LayerCastSettings.channel());
        if (this.directory != null && wantedChannel.equals(this.configuredChannel)) {
            return true;
        }
        if (this.directory != null) {
            this.shutdown();
        }
        long now = System.currentTimeMillis();
        if (now - this.lastDirectoryAttempt < RETRY_AFTER_MS) {
            return false;
        }
        this.lastDirectoryAttempt = now;
        try {
            String backendName = RenderCompat.backendName();
            this.backend = backendName.toLowerCase(java.util.Locale.ROOT).contains("vulkan")
                ? Protocol.BACKEND_VULKAN : Protocol.BACKEND_OPENGL;
            // Several games on one computer each need their own channel: if another running Minecraft already
            // shares on the configured one, use <channel>-2, -3, ... and tell the player which one OBS must use.
            String base = wantedChannel.length() > Protocol.CHANNEL_MAX_LEN - 2
                ? wantedChannel.substring(0, Protocol.CHANNEL_MAX_LEN - 2) : wantedChannel;
            for (int n = 1; n <= MAX_CHANNEL_SUFFIX; n++) {
                String candidate = n == 1 ? wantedChannel : Protocol.sanitizeChannel(base + "-" + n);
                Directory claimed = Directory.claim(candidate, this.backend, LayerCast.producerDescription(backendName), Layers.all(),
                    layer -> this.channels[layer.index()] == null || this.channels[layer.index()].available);
                if (claimed == null) {
                    continue;
                }
                this.directory = claimed;
                this.channelName = candidate;
                this.configuredChannel = wantedChannel;
                LayerCast.LOGGER.info("Sharing layers on channel '{}' ({}, {})", candidate, claimed.name(), backendName);
                if (!candidate.equals(wantedChannel)) {
                    LayerCast.LOGGER.warn("Channel '{}' is used by another running Minecraft; this one shares on '{}'", wantedChannel, candidate);
                    this.channelNotice = true; // shown once the player is in a world (text cannot be drawn while loading)
                }
                return true;
            }
            LayerCast.LOGGER.error("Channels '{}' to '{}-{}' are all used by other running Minecraft instances", wantedChannel,
                wantedChannel, MAX_CHANNEL_SUFFIX);
            return false;
        } catch (Throwable t) {
            LayerCast.LOGGER.error("Could not create the shared layer directory", t);
            this.directory = null;
            return false;
        }
    }

    /** Tells the player once, in game, that this game shares on another channel than the configured one. */
    public void showPendingNotice() {
        if (this.channelNotice && this.directory != null && this.configuredChannel != null && !this.configuredChannel.equals(this.channelName)) {
            this.channelNotice = false;
            LayerCastSettings.channelInUse(this.configuredChannel, this.channelName);
        }
    }

    /** The channel this game actually shares on (may differ from the configured one), or {@code null}. */
    public @org.jspecify.annotations.Nullable String channelName() {
        return this.directory != null ? this.channelName : null;
    }

    public void shutdown() {
        Directory directory = this.directory;
        if (directory != null && !directory.owned()) {
            this.directory = null; // never write into a directory another game has taken over
        }
        for (Channel channel : this.channels) {
            if (channel != null) {
                channel.release(channel.idleState());
            }
        }
        if (directory != null) {
            try {
                directory.close();
            } catch (Throwable t) {
                LayerCast.LOGGER.warn("Closing the shared directory failed", t);
            }
        }
        this.directory = null;
    }

    /** Number of layers currently shared with at least one published frame. */
    public int activeLayerCount() {
        int count = 0;
        for (Channel channel : this.channels) {
            if (channel != null && channel.transport != null && channel.publishedSlot >= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many layers OBS reads from this game: {@code shown} are displayed by a visible source, {@code attached}
     * have a source at all (only counted by plugins that report hidden sources; never less than {@code shown}).
     * Render thread.
     */
    public ObsUse obsUse() {
        if (this.directory == null) {
            return new ObsUse(0, 0);
        }
        long nowMs = System.currentTimeMillis();
        int shown = 0;
        int attached = 0;
        for (Channel channel : this.channels) {
            if (channel == null) {
                continue;
            }
            int index = channel.layer.index();
            boolean isShown = nowMs - this.directory.consumerHeartbeat(index) < Protocol.CONSUMER_TIMEOUT_MS;
            if (isShown) {
                shown++;
            }
            if (isShown || nowMs - this.directory.consumerAttached(index) < Protocol.CONSUMER_TIMEOUT_MS) {
                attached++;
            }
        }
        return new ObsUse(shown, attached);
    }

    public record ObsUse(int shown, int attached) {
    }

    public List<String> describe() {
        List<String> lines = new java.util.ArrayList<>();
        for (Channel channel : this.channels) {
            if (channel != null && channel.transport != null) {
                lines.add(channel.layer.id() + ": " + channel.transport.width() + "x" + channel.transport.height()
                    + " @" + channel.fps + " fps via " + channel.transport.getClass().getSimpleName() + ", frame " + channel.frame
                    + ", skipped " + channel.skipped + ", dropped " + channel.dropped);
            }
        }
        return lines;
    }

    private record Pending(int slot, long frame, GpuFence fence) {
    }

    private final class Channel {
        final Layer layer;
        Transport transport;
        /** GPU sharing failed for this layer (creation error or OBS could not open the handles). */
        boolean gpuUnavailable;
        boolean errorLogged;
        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        int slotCount;
        int publishedSlot = -1;
        long frame;
        long lastCaptureNs;
        long lastWantedMs;
        long retryAtMs;
        int fps;
        /** How late the next capture is (negative: not due yet); {@code Long.MIN_VALUE}: not wanted. */
        long overdueNs;
        long joinNs;
        /** The transport needs its {@code endFrame} call (a slot was reserved or copied this frame). */
        boolean submittedThisFrame;
        /** Slot picked by {@link #reserve} for this frame's copy, or -1. */
        int reservedSlot = -1;
        /** The last frame copied into the current surfaces was fully transparent. */
        boolean lastEmpty;
        /** Captures skipped because the layer stayed empty (diagnostics). */
        long skipped;
        /** Due, but no free slot (diagnostics). */
        long dropped;
        /** Offered to OBS (not a layer that stays merged into the GUI layer). */
        boolean available;

        Channel(Layer layer) {
            this.layer = layer;
        }

        int idleState() {
            return this.available ? Protocol.STATE_IDLE : Protocol.STATE_EMPTY;
        }

        private boolean wantsCpu() {
            return switch (LayerCastSettings.transportMode()) {
                case CPU -> true;
                case GPU -> false;
                case AUTO -> this.gpuUnavailable;
            };
        }

        private Transport createTransport() {
            if (!this.wantsCpu() && !Transports.gpuSupported(SharingService.this.backend)
                && LayerCastSettings.transportMode() == LayerCastSettings.TransportMode.AUTO) {
                this.gpuUnavailable = true;
                if (!noGpuLogged) {
                    noGpuLogged = true;
                    LayerCast.LOGGER.info("No zero-copy GPU sharing on {} yet; layers are shared through shared memory", Native.OS);
                }
            }
            if (!this.wantsCpu()) {
                try {
                    return Transports.createGpu(SharingService.this.backend, this.layer);
                } catch (Throwable t) {
                    if (LayerCastSettings.transportMode() == LayerCastSettings.TransportMode.GPU) {
                        throw t;
                    }
                    this.gpuUnavailable = true;
                    LayerCast.LOGGER.warn("GPU sharing unavailable for layer '{}', using the CPU fallback: {}", this.layer.id(), t.toString());
                }
            }
            return Transports.createCpu();
        }

        /** Picks the slot of this frame's copy early, when the surfaces already match the frame. */
        void reserve(int width, int height) {
            this.reservedSlot = -1;
            if (this.transport == null || this.transport instanceof CpuTransport != this.wantsCpu() || this.slotCount == 0
                || this.transport.width() != width || this.transport.height() != height) {
                return; // (re)created on submit
            }
            int slot = this.pickSlot();
            if (slot >= 0) {
                this.transport.reserve(slot);
                this.reservedSlot = slot;
                this.submittedThisFrame = true;
            }
        }

        void submit(GpuTexture source, boolean empty) {
            int width = source.getWidth(0);
            int height = source.getHeight(0);
            if (this.transport != null && this.transport instanceof CpuTransport != this.wantsCpu()) {
                this.release(this.idleState()); // transport mode changed
            }
            if (this.transport == null) {
                this.transport = this.createTransport();
            }
            if (this.transport.width() != width || this.transport.height() != height || this.slotCount == 0) {
                this.reallocate(width, height);
            }
            int slot = this.reservedSlot >= 0 ? this.reservedSlot : this.pickSlot();
            this.reservedSlot = -1;
            if (slot < 0) {
                this.dropped++;
                return; // every slot is in flight or being read; drop this frame
            }
            long start = LayerProfiler.start();
            this.transport.copy(source, slot);
            LayerProfiler.detail("transport copy", start);
            start = LayerProfiler.start();
            GpuFence fence = RenderSystem.getDevice().createCommandEncoder().createFence();
            LayerProfiler.detail("fence", start);
            this.pending.add(new Pending(slot, ++this.frame, fence));
            this.lastCaptureNs = System.nanoTime();
            this.submittedThisFrame = true;
            this.lastEmpty = empty;
        }

        private void reallocate(int width, int height) {
            this.reservedSlot = -1;
            this.lastEmpty = false;
            this.dropPending();
            Directory directory = SharingService.this.directory;
            this.slotCount = LayerCastSettings.slotCount();
            this.transport.prepare(SharingService.this.channelName, this.layer.index(), directory.nextGeneration(this.layer.index()));
            long[] handles;
            try {
                handles = this.transport.allocate(width, height, this.slotCount);
            } catch (Throwable t) {
                this.slotCount = 0;
                throw t;
            }
            int flags = (this.transport.flipY() ? Protocol.FLAG_FLIP_Y : 0)
                | (this.layer.opaque() ? Protocol.FLAG_OPAQUE : Protocol.FLAG_PREMULTIPLIED);
            directory.configure(this.layer.index(), Protocol.STATE_ACTIVE, flags, width, height,
                this.transport.format(), this.transport.protocolId(), handles, this.fps);
            this.publishedSlot = -1;
            LayerCast.LOGGER.info("Layer '{}' shares {}x{} via {}", this.layer.id(), width, height, this.transport.getClass().getSimpleName());
        }

        private int pickSlot() {
            int reading = SharingService.this.directory.consumerReadingSlot(this.layer.index());
            for (int i = 1; i <= this.slotCount; i++) {
                int candidate = (Math.max(this.publishedSlot, 0) + i) % this.slotCount;
                if (candidate == this.publishedSlot || candidate == reading || this.isPending(candidate)
                    || this.transport.isSlotBusy(candidate)) {
                    continue;
                }
                return candidate;
            }
            return -1;
        }

        private boolean isPending(int slot) {
            for (Pending p : this.pending) {
                if (p.slot() == slot) {
                    return true;
                }
            }
            return false;
        }

        void pollFences() {
            if (this.transport != null && !(this.transport instanceof CpuTransport)
                && LayerCastSettings.transportMode() == LayerCastSettings.TransportMode.AUTO
                && SharingService.this.directory.consumerFailed(this.layer.index())) {
                LayerCast.LOGGER.warn("OBS could not open the GPU surfaces of layer '{}' (different GPU?); switching to the CPU fallback", this.layer.id());
                this.gpuUnavailable = true;
                this.release(this.idleState());
                return;
            }
            while (!this.pending.isEmpty()) {
                Pending head = this.pending.peekFirst();
                if (!head.fence().awaitCompletion(0L)) {
                    return;
                }
                if (this.transport == null) {
                    this.dropPending();
                    return;
                }
                try {
                    if (!this.transport.isSlotReady(head.slot())) {
                        return; // e.g. CPU copy still running; keep order and look again next frame
                    }
                    this.pending.pollFirst();
                    head.fence().close();
                    SharingService.this.directory.publish(this.layer.index(), head.frame(), head.slot());
                    this.publishedSlot = head.slot();
                } catch (Throwable t) {
                    this.pending.pollFirst();
                    head.fence().close();
                    this.logError("Publishing a frame failed", t);
                }
            }
        }

        void logError(String message, Throwable t) {
            if (!this.errorLogged) {
                this.errorLogged = true;
                LayerCast.LOGGER.error("{} for layer '{}'", message, this.layer.id(), t);
            } else {
                LayerCast.LOGGER.warn("{} for layer '{}': {}", message, this.layer.id(), t.toString());
            }
        }

        private void dropPending() {
            for (Pending p : this.pending) {
                p.fence().close();
            }
            this.pending.clear();
        }

        void release(int state) {
            this.dropPending();
            this.reservedSlot = -1;
            this.lastEmpty = false;
            if (this.transport != null) {
                try {
                    this.transport.close();
                } catch (Throwable t) {
                    LayerCast.LOGGER.warn("Releasing transport of '{}' failed", this.layer.id(), t);
                }
                this.transport = null;
            }
            this.slotCount = 0;
            this.publishedSlot = -1;
            if (SharingService.this.directory != null) {
                SharingService.this.directory.configure(this.layer.index(), state, 0, 0, 0, Protocol.FORMAT_NONE,
                    Protocol.TRANSPORT_NONE, new long[0], 0);
            }
        }
    }
}
