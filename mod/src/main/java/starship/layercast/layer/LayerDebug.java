package starship.layercast.layer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import starship.layercast.LayerCast;
import starship.layercast.compat.RenderCompat;
import net.minecraft.util.Util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostics: writes the content of every layer rendered in the next captured frame to PNG files
 * (straight alpha, top-down), using the same asynchronous read-back Minecraft uses for screenshots.
 */
public final class LayerDebug {
    private static volatile Path pendingDump;
    private static Path activeDump;
    private static final AtomicInteger OUTSTANDING = new AtomicInteger();

    private LayerDebug() {
    }

    /** Requests a dump of all layers rendered in the next frame that captures anything. */
    public static void requestDump(Path directory) {
        pendingDump = directory;
    }

    public static boolean dumpRequested() {
        return pendingDump != null;
    }

    public static boolean dumpInProgress() {
        return pendingDump != null || activeDump != null || OUTSTANDING.get() > 0;
    }

    public static void beginFrame() {
        activeDump = pendingDump;
        pendingDump = null;
    }

    public static void endFrame() {
        activeDump = null;
    }

    public static boolean active() {
        return activeDump != null;
    }

    /** Records a read-back of the texture. Must be called right after the texture content was produced. */
    public static void capture(Layer layer, GpuTexture texture) {
        capture(layer.id(), layer.opaque(), texture);
    }

    /**
     * Records the finished game frame (world + vanilla GUI) of the dump frame as {@code _frame.png}, so tests can
     * check that the world and the GUI layers add up to exactly what the player sees.
     */
    public static void captureFrame(GpuTexture texture) {
        capture("_frame", true, texture);
    }

    private static void capture(String name, boolean opaque, GpuTexture texture) {
        Path dir = activeDump;
        if (dir == null) {
            return;
        }
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "LayerCast dump " + name,
            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
        OUTSTANDING.incrementAndGet();
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (RenderCompat.Mapped view = RenderCompat.mapForRead(buffer)) {
                ByteBuffer data = view.data().order(ByteOrder.LITTLE_ENDIAN);
                NativeImage image = new NativeImage(width, height, false);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int abgr = data.getInt((x + y * width) * 4);
                        if (!opaque) {
                            abgr = unpremultiply(abgr);
                        } else {
                            abgr |= 0xFF000000;
                        }
                        image.setPixelABGR(x, height - y - 1, abgr);
                    }
                }
                Util.ioPool().execute(() -> {
                    try (image) {
                        Files.createDirectories(dir);
                        image.writeToFile(dir.resolve(name + ".png"));
                    } catch (Exception e) {
                        LayerCast.LOGGER.error("Could not write layer dump", e);
                    } finally {
                        OUTSTANDING.decrementAndGet();
                    }
                });
            } catch (Throwable t) {
                OUTSTANDING.decrementAndGet();
                LayerCast.LOGGER.error("Layer dump read-back failed", t);
            } finally {
                buffer.close();
            }
        }, 0);
    }

    private static int unpremultiply(int abgr) {
        int a = abgr >>> 24;
        if (a == 0) {
            return 0;
        }
        if (a == 255) {
            return abgr;
        }
        int r = Math.min(255, (abgr & 0xFF) * 255 / a);
        int g = Math.min(255, ((abgr >>> 8) & 0xFF) * 255 / a);
        int b = Math.min(255, ((abgr >>> 16) & 0xFF) * 255 / a);
        return a << 24 | b << 16 | g << 8 | r;
    }
}
