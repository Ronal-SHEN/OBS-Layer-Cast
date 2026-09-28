package dev.layercast.share.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static dev.layercast.share.ffm.Native.C_INT;
import static dev.layercast.share.ffm.Native.C_POINTER;

/**
 * CoreFoundation / IOSurface / CGL bindings needed to share frames through global IOSurfaces.
 */
public final class MacOS {
    private static final String CF_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private static final String IOSURFACE_PATH = "/System/Library/Frameworks/IOSurface.framework/IOSurface";
    private static final String OPENGL_PATH = "/System/Library/Frameworks/OpenGL.framework/OpenGL";

    public static final int PIXEL_FORMAT_BGRA = 0x42475241; // 'BGRA' == kCVPixelFormatType_32BGRA
    private static final int K_CF_NUMBER_SINT32 = 3;

    private static final MethodHandle CF_DICTIONARY_CREATE_MUTABLE;
    private static final MethodHandle CF_DICTIONARY_SET_VALUE;
    private static final MethodHandle CF_NUMBER_CREATE;
    private static final MethodHandle CF_RELEASE;
    private static final MethodHandle IOSURFACE_CREATE;
    private static final MethodHandle IOSURFACE_GET_ID;
    private static final MethodHandle CGL_GET_CURRENT_CONTEXT;
    private static final MethodHandle CGL_TEX_IMAGE_IOSURFACE_2D;

    private static final MemorySegment KEY_CALLBACKS;
    private static final MemorySegment VALUE_CALLBACKS;
    private static final MemorySegment CF_BOOLEAN_TRUE;
    private static final MemorySegment K_WIDTH;
    private static final MemorySegment K_HEIGHT;
    private static final MemorySegment K_BYTES_PER_ELEMENT;
    private static final MemorySegment K_PIXEL_FORMAT;
    private static final MemorySegment K_IS_GLOBAL;

    static {
        SymbolLookup cf = Native.library(CF_PATH);
        SymbolLookup ios = Native.library(IOSURFACE_PATH);
        SymbolLookup gl = Native.library(OPENGL_PATH);

        CF_DICTIONARY_CREATE_MUTABLE = Native.downcall(cf, "CFDictionaryCreateMutable",
            FunctionDescriptor.of(C_POINTER, C_POINTER, ValueLayout.JAVA_LONG, C_POINTER, C_POINTER));
        CF_DICTIONARY_SET_VALUE = Native.downcall(cf, "CFDictionarySetValue",
            FunctionDescriptor.ofVoid(C_POINTER, C_POINTER, C_POINTER));
        CF_NUMBER_CREATE = Native.downcall(cf, "CFNumberCreate",
            FunctionDescriptor.of(C_POINTER, C_POINTER, ValueLayout.JAVA_LONG, C_POINTER));
        CF_RELEASE = Native.downcall(cf, "CFRelease", FunctionDescriptor.ofVoid(C_POINTER));
        KEY_CALLBACKS = Native.globalAddress(cf, "kCFTypeDictionaryKeyCallBacks");
        VALUE_CALLBACKS = Native.globalAddress(cf, "kCFTypeDictionaryValueCallBacks");
        CF_BOOLEAN_TRUE = Native.globalPointer(cf, "kCFBooleanTrue");

        IOSURFACE_CREATE = Native.downcall(ios, "IOSurfaceCreate", FunctionDescriptor.of(C_POINTER, C_POINTER));
        IOSURFACE_GET_ID = Native.downcall(ios, "IOSurfaceGetID", FunctionDescriptor.of(C_INT, C_POINTER));
        K_WIDTH = Native.globalPointer(ios, "kIOSurfaceWidth");
        K_HEIGHT = Native.globalPointer(ios, "kIOSurfaceHeight");
        K_BYTES_PER_ELEMENT = Native.globalPointer(ios, "kIOSurfaceBytesPerElement");
        K_PIXEL_FORMAT = Native.globalPointer(ios, "kIOSurfacePixelFormat");
        K_IS_GLOBAL = Native.globalPointer(ios, "kIOSurfaceIsGlobal");

        CGL_GET_CURRENT_CONTEXT = Native.downcall(gl, "CGLGetCurrentContext", FunctionDescriptor.of(C_POINTER));
        CGL_TEX_IMAGE_IOSURFACE_2D = Native.downcall(gl, "CGLTexImageIOSurface2D",
            FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_INT, C_INT, C_INT, C_INT, C_INT, C_POINTER, C_INT));
    }

    private MacOS() {
    }

    /** Forces class initialisation so missing symbols surface as an early, catchable error. */
    public static void load() {
    }

    /**
     * Creates a global (cross-process look-up-able) BGRA IOSurface. The caller owns the returned reference.
     */
    public static MemorySegment createGlobalSurface(int width, int height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dict = (MemorySegment) CF_DICTIONARY_CREATE_MUTABLE.invokeExact(MemorySegment.NULL, 0L, KEY_CALLBACKS, VALUE_CALLBACKS);
            try {
                putInt(arena, dict, K_WIDTH, width);
                putInt(arena, dict, K_HEIGHT, height);
                putInt(arena, dict, K_BYTES_PER_ELEMENT, 4);
                putInt(arena, dict, K_PIXEL_FORMAT, PIXEL_FORMAT_BGRA);
                CF_DICTIONARY_SET_VALUE.invokeExact(dict, K_IS_GLOBAL, CF_BOOLEAN_TRUE);
                MemorySegment surface = (MemorySegment) IOSURFACE_CREATE.invokeExact(dict);
                if (surface.address() == 0L) {
                    throw new IllegalStateException("IOSurfaceCreate(" + width + "x" + height + ") failed");
                }
                return surface;
            } finally {
                CF_RELEASE.invokeExact(dict);
            }
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private static void putInt(Arena arena, MemorySegment dict, MemorySegment key, int value) throws Throwable {
        MemorySegment boxed = arena.allocateFrom(ValueLayout.JAVA_INT, value);
        MemorySegment number = (MemorySegment) CF_NUMBER_CREATE.invokeExact(MemorySegment.NULL, (long) K_CF_NUMBER_SINT32, boxed);
        try {
            CF_DICTIONARY_SET_VALUE.invokeExact(dict, key, number);
        } finally {
            CF_RELEASE.invokeExact(number);
        }
    }

    public static int surfaceId(MemorySegment surface) {
        try {
            return (int) IOSURFACE_GET_ID.invokeExact(surface);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    public static void release(MemorySegment cfObject) {
        if (cfObject == null || cfObject.address() == 0L) {
            return;
        }
        try {
            CF_RELEASE.invokeExact(cfObject);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    public static MemorySegment currentCglContext() {
        try {
            return (MemorySegment) CGL_GET_CURRENT_CONTEXT.invokeExact();
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** Binds the IOSurface as the storage of the texture currently bound to {@code target}. Returns a CGLError. */
    public static int texImageIOSurface2D(MemorySegment cglContext, int target, int internalFormat, int width, int height,
                                          int format, int type, MemorySegment surface, int plane) {
        try {
            return (int) CGL_TEX_IMAGE_IOSURFACE_2D.invokeExact(cglContext, target, internalFormat, width, height, format, type, surface, plane);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }
}
