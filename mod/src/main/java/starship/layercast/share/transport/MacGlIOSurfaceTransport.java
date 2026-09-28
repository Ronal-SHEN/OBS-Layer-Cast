package starship.layercast.share.transport;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import starship.layercast.share.Protocol;
import starship.layercast.share.ffm.MacOS;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;

import java.lang.foreign.MemorySegment;

/**
 * macOS + OpenGL backend: every slot is a global IOSurface bound to a {@code GL_TEXTURE_RECTANGLE} through
 * {@code CGLTexImageIOSurface2D}. Frames are copied with a framebuffer blit, which also converts the
 * RGBA texture into the BGRA layout OBS expects for IOSurfaces.
 */
public final class MacGlIOSurfaceTransport implements Transport {
    /** A copy was recorded since the last flush (shared by all layers, render thread only). */
    private static boolean flushPending;
    private static final int GL_READ_FRAMEBUFFER = GL30C.GL_READ_FRAMEBUFFER;
    private static final int GL_DRAW_FRAMEBUFFER = GL30C.GL_DRAW_FRAMEBUFFER;

    private Slot[] slots = new Slot[0];
    private int readFbo;
    private int width;
    private int height;

    private record Slot(MemorySegment surface, int surfaceId, int texture, int fbo) {
    }

    public MacGlIOSurfaceTransport() {
        MacOS.load();
    }

    @Override
    public int protocolId() {
        return Protocol.TRANSPORT_IOSURFACE;
    }

    @Override
    public int format() {
        return Protocol.FORMAT_BGRA8;
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
        MemorySegment cgl = MacOS.currentCglContext();
        if (cgl.address() == 0L) {
            throw new IllegalStateException("No current CGL context");
        }
        if (this.readFbo == 0) {
            this.readFbo = GlStateManager.glGenFramebuffers();
        }
        int previousDraw = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
        Slot[] created = new Slot[slotCount];
        long[] handles = new long[slotCount];
        try {
            for (int i = 0; i < slotCount; i++) {
                MemorySegment surface = MacOS.createGlobalSurface(width, height);
                int texture = GL11C.glGenTextures();
                GL11C.glBindTexture(GL31C.GL_TEXTURE_RECTANGLE, texture);
                int err = MacOS.texImageIOSurface2D(cgl, GL31C.GL_TEXTURE_RECTANGLE, GL11C.GL_RGBA8, width, height,
                    GL12C.GL_BGRA, GL12C.GL_UNSIGNED_INT_8_8_8_8_REV, surface, 0);
                GL11C.glBindTexture(GL31C.GL_TEXTURE_RECTANGLE, 0);
                if (err != 0) {
                    GL11C.glDeleteTextures(texture);
                    MacOS.release(surface);
                    throw new IllegalStateException("CGLTexImageIOSurface2D failed: " + err);
                }
                int fbo = GlStateManager.glGenFramebuffers();
                GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
                GL30C.glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL31C.GL_TEXTURE_RECTANGLE, texture, 0);
                int status = GL30C.glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
                created[i] = new Slot(surface, MacOS.surfaceId(surface), texture, fbo);
                handles[i] = Integer.toUnsignedLong(created[i].surfaceId());
                if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("IOSurface framebuffer incomplete: 0x" + Integer.toHexString(status));
                }
            }
        } catch (RuntimeException e) {
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            this.slots = created;
            this.releaseSlots();
            throw e;
        }
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        this.slots = created;
        this.width = width;
        this.height = height;
        return handles;
    }

    @Override
    public void copy(GpuTexture source, int slot) {
        Slot target = this.slots[slot];
        int previousRead = GlStateManager.getFrameBuffer(GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL_DRAW_FRAMEBUFFER);
        GlStateManager._disableScissorTest();
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFbo);
        GL30C.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, ((GlTexture) source).glId(), 0);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.fbo());
        GL30C.glBlitFramebuffer(0, 0, this.width, this.height, 0, 0, this.width, this.height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        // Detach so the source texture can be deleted/resized freely by Minecraft.
        GL30C.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        flushPending = true;
    }

    @Override
    public void endFrame() {
        // IOSurface contents written by GL become visible to other processes once flushed; one flush covers every
        // layer of the frame.
        if (flushPending) {
            flushPending = false;
            GL11C.glFlush();
        }
    }

    private void releaseSlots() {
        for (Slot slot : this.slots) {
            if (slot == null) {
                continue;
            }
            GlStateManager._glDeleteFramebuffers(slot.fbo());
            GL11C.glDeleteTextures(slot.texture());
            MacOS.release(slot.surface());
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
    }
}
