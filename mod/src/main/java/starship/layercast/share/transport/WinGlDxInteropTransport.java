package starship.layercast.share.transport;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import starship.layercast.LayerCast;
import starship.layercast.LayerProfiler;
import starship.layercast.share.Protocol;
import starship.layercast.share.ffm.Native;
import starship.layercast.share.ffm.Windows;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static starship.layercast.share.ffm.Native.C_POINTER;

/**
 * Windows + OpenGL backend, identical in principle to OBS' own OpenGL game capture hook:
 * a private D3D11 device on the same GPU as Minecraft's GL context creates shareable textures, which
 * {@code WGL_NV_DX_interop2} exposes to GL. Frames are copied into them between lock/unlock, and OBS opens the
 * global share handles with {@code gs_texture_open_shared}.
 * <p>
 * Locking and unlocking synchronise GL with Direct3D and cost around 50-80 us of blocking CPU time each, so all
 * surfaces written in a frame are locked with one call (on the first copy, from the slots reserved before the layers
 * were drawn) and unlocked with one call at the end of the frame, instead of once per layer.
 */
public final class WinGlDxInteropTransport implements Transport {
    private static final int GL_READ_FRAMEBUFFER = GL30C.GL_READ_FRAMEBUFFER;
    private static final int GL_DRAW_FRAMEBUFFER = GL30C.GL_DRAW_FRAMEBUFFER;
    private static boolean copyImage;

    private final Device device;
    private final Windows.D3D11Device d3d;
    private final Windows.DxInterop wgl;
    private final MemorySegment interopDevice;
    private Slot[] slots = new Slot[0];
    private int readFbo;
    private int width;
    private int height;

    private record Slot(MemorySegment texture, long shareHandle, int glTexture, MemorySegment interopObject, int fbo) {
    }

    public WinGlDxInteropTransport() {
        copyImage = GL.getCapabilities().glCopyImageSubData != 0L;
        this.device = Device.acquire();
        this.d3d = this.device.d3d;
        this.wgl = this.device.wgl;
        this.interopDevice = this.device.interopDevice;
    }

    /**
     * One D3D11 device and interop handle shared by every layer (reference counted, render thread only).
     */
    private static final class Device {
        private static Device instance;
        private int references;
        final Windows.D3D11Device d3d;
        final Windows.DxInterop wgl;
        final MemorySegment interopDevice;
        final MethodHandle flush;
        boolean flushPending;
        private final Arena arena = Arena.ofShared();
        private MemorySegment handleArray = this.arena.allocate(C_POINTER, 64);
        /** Surfaces that will be written this frame and are not locked yet. */
        private final java.util.List<MemorySegment> reserved = new java.util.ArrayList<>();
        /** Surfaces locked for GL right now. */
        private final java.util.List<MemorySegment> locked = new java.util.ArrayList<>();

        private Device() {
            Windows.load();
            this.wgl = Windows.DxInterop.load();
            MemorySegment adapter = MemorySegment.NULL;
            long luid = glAdapterLuid();
            if (luid != 0L) {
                adapter = Windows.findAdapter(luid);
                if (adapter.address() == 0L) {
                    LayerCast.LOGGER.warn("No DXGI adapter matches the OpenGL device LUID {}; using the default adapter", Long.toHexString(luid));
                }
            }
            try {
                this.d3d = Windows.createDevice(adapter);
            } finally {
                Windows.release(adapter);
            }
            MemorySegment opened;
            try {
                opened = (MemorySegment) this.wgl.openDevice.invokeExact(this.d3d.device());
            } catch (Throwable t) {
                this.releaseD3d();
                throw Native.rethrow(t);
            }
            if (opened.address() == 0L) {
                this.releaseD3d();
                throw new IllegalStateException("wglDXOpenDeviceNV failed");
            }
            this.interopDevice = opened;
            this.flush = Windows.contextFlush(this.d3d.context());
            LayerCast.LOGGER.info("Created D3D11 interop device (feature level 0x{}, GL adapter LUID {})",
                Integer.toHexString(this.d3d.featureLevel()), Long.toHexString(luid));
        }

        static Device acquire() {
            if (instance == null) {
                instance = new Device();
            }
            instance.references++;
            return instance;
        }

        void release() {
            if (--this.references > 0) {
                return;
            }
            this.unlockAll();
            try {
                int ignored = (int) this.wgl.closeDevice.invokeExact(this.interopDevice);
            } catch (Throwable t) {
                LayerCast.LOGGER.warn("wglDXCloseDeviceNV failed", t);
            }
            this.releaseD3d();
            this.arena.close();
            if (instance == this) {
                instance = null;
            }
        }

        void reserve(MemorySegment object) {
            if (!this.locked.contains(object) && !this.reserved.contains(object)) {
                this.reserved.add(object);
            }
        }

        /** Makes sure GL may write the surface, locking every reserved surface along with it in one call. */
        void ensureLocked(MemorySegment object) {
            if (this.locked.contains(object)) {
                return;
            }
            this.reserve(object);
            long start = LayerProfiler.start();
            int ok = this.call(this.wgl.lockObjects, this.reserved);
            LayerProfiler.detail("interop lock", start);
            if (ok == 0) {
                this.reserved.clear();
                throw new IllegalStateException("wglDXLockObjectsNV failed");
            }
            this.locked.addAll(this.reserved);
            this.reserved.clear();
        }

        /** End of the frame: hands every surface written this frame back to Direct3D and submits the D3D side. */
        void endFrame() {
            this.reserved.clear();
            this.unlockAll();
            this.flushIfPending();
        }

        private void unlockAll() {
            if (this.locked.isEmpty()) {
                return;
            }
            long start = LayerProfiler.start();
            this.call(this.wgl.unlockObjects, this.locked);
            LayerProfiler.detail("interop unlock", start);
            this.locked.clear();
        }

        /** Before a surface is unregistered: it must not stay locked or reserved. */
        void forget(MemorySegment object) {
            this.reserved.remove(object);
            if (this.locked.remove(object)) {
                this.call(this.wgl.unlockObjects, java.util.List.of(object));
            }
        }

        private int call(MethodHandle lockOrUnlock, java.util.List<MemorySegment> objects) {
            if (this.handleArray.byteSize() < objects.size() * C_POINTER.byteSize()) {
                this.handleArray = this.arena.allocate(C_POINTER, objects.size() * 2L);
            }
            for (int i = 0; i < objects.size(); i++) {
                this.handleArray.setAtIndex(C_POINTER, i, objects.get(i));
            }
            try {
                return (int) lockOrUnlock.invokeExact(this.interopDevice, objects.size(), this.handleArray);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        private void releaseD3d() {
            Windows.release(this.d3d.context());
            Windows.release(this.d3d.device());
        }

        void flushIfPending() {
            if (!this.flushPending) {
                return;
            }
            this.flushPending = false;
            long start = LayerProfiler.start();
            try {
                this.flush.invokeExact(this.d3d.context());
                LayerProfiler.detail("d3d flush", start);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }
    }

    /** LUID of the GPU running the GL context (0 if the driver does not expose it). */
    private static long glAdapterLuid() {
        if (!GL.getCapabilities().GL_EXT_memory_object_win32) {
            return 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer luid = stack.calloc(8);
            EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, luid);
            return luid.order(ByteOrder.LITTLE_ENDIAN).getLong(0);
        }
    }

    @Override
    public int protocolId() {
        return Protocol.TRANSPORT_D3D11_KMT;
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
    public long[] allocate(int width, int height, int slotCount) {
        this.releaseSlots();
        if (this.readFbo == 0) {
            this.readFbo = GlStateManager.glGenFramebuffers();
        }
        Slot[] created = new Slot[slotCount];
        long[] handles = new long[slotCount];
        int previousDraw = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
        try {
            for (int i = 0; i < slotCount; i++) {
                MemorySegment texture = Windows.createSharedTexture(this.d3d.device(), width, height);
                long share;
                try {
                    share = Windows.sharedHandle(texture);
                } catch (RuntimeException e) {
                    Windows.release(texture);
                    throw e;
                }
                int glTexture = GL11C.glGenTextures();
                MemorySegment object = (MemorySegment) this.wgl.registerObject.invokeExact(this.interopDevice, texture, glTexture,
                    GL11C.GL_TEXTURE_2D, Windows.WGL_ACCESS_WRITE_DISCARD_NV);
                if (object.address() == 0L) {
                    GL11C.glDeleteTextures(glTexture);
                    Windows.release(texture);
                    throw new IllegalStateException("wglDXRegisterObjectNV failed (GL error " + GL11C.glGetError() + ")");
                }
                int fbo = GlStateManager.glGenFramebuffers();
                created[i] = new Slot(texture, share, glTexture, object, fbo);
                handles[i] = share;
                this.device.ensureLocked(object);
                try {
                    GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
                    GL30C.glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, glTexture, 0);
                    int status = GL30C.glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
                    if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                        throw new IllegalStateException("Interop framebuffer incomplete: 0x" + Integer.toHexString(status));
                    }
                } finally {
                    this.device.forget(object);
                }
            }
        } catch (Throwable t) {
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            this.slots = created;
            this.releaseSlots();
            throw Native.rethrow(t);
        }
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        this.slots = created;
        this.width = width;
        this.height = height;
        return handles;
    }

    @Override
    public void reserve(int slot) {
        this.device.reserve(this.slots[slot].interopObject());
    }

    @Override
    public void copy(GpuTexture source, int slot) {
        Slot target = this.slots[slot];
        this.device.ensureLocked(target.interopObject());
        int sourceId = ((GlTexture) source).glId();
        if (copyImage) {
            // A plain texture copy: no framebuffer (re)binding, which costs more CPU time than the copy itself.
            GL43C.glCopyImageSubData(sourceId, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                target.glTexture(), GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, this.width, this.height, 1);
        } else {
            int previousRead = GlStateManager.getFrameBuffer(GL_READ_FRAMEBUFFER);
            int previousDraw = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
            GlStateManager._disableScissorTest();
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFbo);
            GL30C.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, sourceId, 0);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.fbo());
            GL30C.glBlitFramebuffer(0, 0, this.width, this.height, 0, 0, this.width, this.height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            GL30C.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        }
        this.device.flushPending = true;
    }

    @Override
    public void endFrame() {
        // Hand the surfaces back to Direct3D and submit the D3D side of the interop synchronisation once per frame
        // for all layers, like OBS' GL hook does with Present().
        this.device.endFrame();
    }

    private void releaseSlots() {
        for (Slot slot : this.slots) {
            if (slot == null) {
                continue;
            }
            try {
                this.device.forget(slot.interopObject());
                int ignored = (int) this.wgl.unregisterObject.invokeExact(this.interopDevice, slot.interopObject());
            } catch (Throwable t) {
                LayerCast.LOGGER.warn("wglDXUnregisterObjectNV failed", t);
            }
            GlStateManager._glDeleteFramebuffers(slot.fbo());
            GL11C.glDeleteTextures(slot.glTexture());
            Windows.release(slot.texture());
        }
        this.slots = new Slot[0];
    }

    @Override
    public void close() {
        this.releaseSlots();
        if (this.readFbo != 0) {
            GlStateManager._glDeleteFramebuffers(this.readFbo);
            this.readFbo = 0;
        }
        this.device.release();
    }
}
