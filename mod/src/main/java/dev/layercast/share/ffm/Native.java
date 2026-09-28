package dev.layercast.share.ffm;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;

/**
 * Small helpers around the Java 22+ Foreign Function &amp; Memory API. All native interop of the mod goes through
 * FFM, so no platform binaries have to be shipped inside the mod jar.
 */
public final class Native {
    public static final Linker LINKER = Linker.nativeLinker();
    public static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong C_LONG_LONG = ValueLayout.JAVA_LONG;
    public static final ValueLayout C_SIZE_T = ValueLayout.JAVA_LONG;
    public static final AddressLayout C_POINTER = ValueLayout.ADDRESS;

    public enum Os { WINDOWS, MACOS, LINUX, OTHER }

    public static final Os OS = detectOs();

    private Native() {
    }

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MACOS;
        }
        if (name.contains("linux")) {
            return Os.LINUX;
        }
        return Os.OTHER;
    }

    /** Loads a native library for the lifetime of the JVM. */
    public static SymbolLookup library(String name) {
        return SymbolLookup.libraryLookup(name, Arena.global());
    }

    public static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Missing native symbol " + name));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    public static Optional<MethodHandle> optionalDowncall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return lookup.find(name).map(address -> LINKER.downcallHandle(address, descriptor));
    }

    /** Creates a handle for a native function pointer, e.g. a COM vtable entry or a wglGetProcAddress result. */
    public static MethodHandle downcall(MemorySegment functionPointer, FunctionDescriptor descriptor) {
        if (functionPointer.equals(MemorySegment.NULL)) {
            throw new UnsatisfiedLinkError("NULL function pointer");
        }
        return LINKER.downcallHandle(functionPointer, descriptor);
    }

    /** Reads a pointer-sized global variable exported by a library (e.g. a {@code CFStringRef} constant). */
    public static MemorySegment globalPointer(SymbolLookup lookup, String name) {
        MemorySegment address = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Missing native symbol " + name));
        return address.reinterpret(C_POINTER.byteSize()).get(C_POINTER, 0);
    }

    public static MemorySegment globalAddress(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Missing native symbol " + name));
    }

    /** Wraps {@link Throwable}s thrown by {@code MethodHandle.invokeExact} into an unchecked exception. */
    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
