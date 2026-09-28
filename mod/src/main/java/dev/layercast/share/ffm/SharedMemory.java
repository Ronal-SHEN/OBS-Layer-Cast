package dev.layercast.share.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

import static dev.layercast.share.ffm.Native.C_INT;
import static dev.layercast.share.ffm.Native.C_POINTER;
import static dev.layercast.share.ffm.Native.C_SIZE_T;

/**
 * A named, pagefile/tmpfs-backed shared memory block (never disk backed).
 * Windows: {@code CreateFileMappingW(INVALID_HANDLE_VALUE)}; POSIX: {@code shm_open} + {@code mmap}.
 */
public final class SharedMemory implements AutoCloseable {
    private final String name;
    private final MemorySegment segment;
    private final long handle;
    private final boolean owner;
    private boolean closed;

    private SharedMemory(String name, MemorySegment segment, long handle, boolean owner) {
        this.name = name;
        this.segment = segment;
        this.handle = handle;
        this.owner = owner;
    }

    /** Creates the block or opens it if it already exists. The returned memory is not cleared. */
    public static SharedMemory createOrOpen(String name, long size) {
        return Native.OS == Native.Os.WINDOWS ? Win.create(name, size) : Posix.create(name, size);
    }

    /**
     * Opens an existing block for reading only.
     *
     * @return {@code null} if there is no such block or it is smaller than {@code size}
     */
    public static @Nullable SharedMemory openReadOnly(String name, long size) {
        return Native.OS == Native.Os.WINDOWS ? Win.openReadOnly(name, size) : Posix.openReadOnly(name, size);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public String name() {
        return this.name;
    }

    /**
     * Unmaps the block. When {@code unlink} is set the POSIX name is removed as well, so that consumers
     * reopening by name get a fresh block from the next producer instead of this stale one.
     */
    public void close(boolean unlink) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (Native.OS == Native.Os.WINDOWS) {
            Win.close(this);
        } else {
            Posix.close(this, unlink && this.owner);
        }
    }

    @Override
    public void close() {
        this.close(false);
    }

    private static final class Posix {
        private static final int O_RDONLY = 0;
        private static final int O_RDWR = 2;
        private static final int O_CREAT = Native.OS == Native.Os.MACOS ? 0x200 : 0x40;
        private static final int PROT_READ = 1;
        private static final int PROT_READ_WRITE = 3;
        /** Offset of {@code st_size} in {@code struct stat} (macOS arm64/x86_64; Linux x86_64/aarch64). */
        private static final long ST_SIZE_OFFSET = Native.OS == Native.Os.MACOS ? 96 : 48;
        private static final int MAP_SHARED = 1;

        private static final MethodHandle SHM_OPEN;
        private static final MethodHandle SHM_UNLINK;
        private static final MethodHandle FTRUNCATE;
        private static final MethodHandle MMAP;
        private static final MethodHandle MUNMAP;
        private static final MethodHandle CLOSE;
        /** {@code null} with glibc < 2.33, which only exports {@code __fxstat}. */
        private static final @Nullable MethodHandle FSTAT;

        static {
            SymbolLookup libc = Native.LINKER.defaultLookup();
            SymbolLookup shmLib = libc;
            if (libc.find("shm_open").isEmpty() && Native.OS == Native.Os.LINUX) {
                shmLib = Native.library("librt.so.1"); // glibc < 2.34
            }
            SHM_OPEN = Native.downcall(shmLib, "shm_open", FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_INT),
                Linker.Option.firstVariadicArg(2));
            SHM_UNLINK = Native.downcall(shmLib, "shm_unlink", FunctionDescriptor.of(C_INT, C_POINTER));
            FTRUNCATE = Native.downcall(libc, "ftruncate", FunctionDescriptor.of(C_INT, C_INT, ValueLayout.JAVA_LONG));
            MMAP = Native.downcall(libc, "mmap",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_SIZE_T, C_INT, C_INT, C_INT, ValueLayout.JAVA_LONG));
            MUNMAP = Native.downcall(libc, "munmap", FunctionDescriptor.of(C_INT, C_POINTER, C_SIZE_T));
            CLOSE = Native.downcall(libc, "close", FunctionDescriptor.of(C_INT, C_INT));
            // macOS: fstat64 has the 64-bit inode layout on both architectures (plain fstat is the old one on x86_64).
            String fstat = Native.OS == Native.Os.MACOS ? "fstat64" : "fstat";
            FSTAT = libc.find(fstat).isPresent()
                ? Native.downcall(libc, fstat, FunctionDescriptor.of(C_INT, C_INT, C_POINTER))
                : null;
        }

        static @Nullable SharedMemory openReadOnly(String name, long size) {
            if (FSTAT == null) {
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                int fd = (int) SHM_OPEN.invokeExact(arena.allocateFrom(name), O_RDONLY, 0);
                if (fd < 0) {
                    return null;
                }
                try {
                    // Mapping more than the object holds would fault on access (it may not be sized yet).
                    MemorySegment stat = arena.allocate(256, 8);
                    if ((int) FSTAT.invokeExact(fd, stat) != 0 || stat.get(ValueLayout.JAVA_LONG, ST_SIZE_OFFSET) < size) {
                        return null;
                    }
                    MemorySegment address = (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, size, PROT_READ, MAP_SHARED, fd, 0L);
                    if (address.address() == -1L || address.address() == 0L) {
                        return null;
                    }
                    return new SharedMemory(name, address.reinterpret(size), 0, false);
                } finally {
                    int ignored = (int) CLOSE.invokeExact(fd);
                }
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        static SharedMemory create(String name, long size) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cName = arena.allocateFrom(name);
                int fd = (int) SHM_OPEN.invokeExact(cName, O_RDWR | O_CREAT, 0600);
                if (fd < 0) {
                    throw new IllegalStateException("shm_open(" + name + ") failed");
                }
                try {
                    // macOS only allows sizing a shm object once; a failure here means it already has its size.
                    int ignored = (int) FTRUNCATE.invokeExact(fd, size);
                    MemorySegment address = (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, size, PROT_READ_WRITE, MAP_SHARED, fd, 0L);
                    if (address.address() == -1L || address.address() == 0L) {
                        throw new IllegalStateException("mmap(" + name + ") failed");
                    }
                    return new SharedMemory(name, address.reinterpret(size), 0, true);
                } finally {
                    int ignored = (int) CLOSE.invokeExact(fd);
                }
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        static void close(SharedMemory memory, boolean unlink) {
            try {
                int ignored = (int) MUNMAP.invokeExact(memory.segment, memory.segment.byteSize());
                if (unlink) {
                    try (Arena arena = Arena.ofConfined()) {
                        ignored = (int) SHM_UNLINK.invokeExact(arena.allocateFrom(memory.name));
                    }
                }
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }
    }

    private static final class Win {
        private static final int PAGE_READWRITE = 0x04;
        private static final int FILE_MAP_READ = 0x04;
        private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
        private static final MethodHandle CREATE_FILE_MAPPING;
        private static final MethodHandle OPEN_FILE_MAPPING;
        private static final MethodHandle MAP_VIEW_OF_FILE;
        private static final MethodHandle UNMAP_VIEW_OF_FILE;
        private static final MethodHandle CLOSE_HANDLE;

        static {
            SymbolLookup kernel32 = Native.library("kernel32");
            CREATE_FILE_MAPPING = Native.downcall(kernel32, "CreateFileMappingW",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER, C_INT, C_INT, C_INT, C_POINTER));
            OPEN_FILE_MAPPING = Native.downcall(kernel32, "OpenFileMappingW",
                FunctionDescriptor.of(C_POINTER, C_INT, C_INT, C_POINTER));
            MAP_VIEW_OF_FILE = Native.downcall(kernel32, "MapViewOfFile",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT, C_INT, C_INT, C_SIZE_T));
            UNMAP_VIEW_OF_FILE = Native.downcall(kernel32, "UnmapViewOfFile", FunctionDescriptor.of(C_INT, C_POINTER));
            CLOSE_HANDLE = Native.downcall(kernel32, "CloseHandle", FunctionDescriptor.of(C_INT, C_POINTER));
        }

        static MemorySegment wide(Arena arena, String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
            MemorySegment segment = arena.allocate(bytes.length + 2L);
            MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
            return segment;
        }

        static SharedMemory create(String name, long size) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment invalidHandle = MemorySegment.ofAddress(-1L);
                MemorySegment mapping = (MemorySegment) CREATE_FILE_MAPPING.invokeExact(invalidHandle, MemorySegment.NULL,
                    PAGE_READWRITE, (int) (size >>> 32), (int) size, wide(arena, name));
                if (mapping.address() == 0L) {
                    throw new IllegalStateException("CreateFileMappingW(" + name + ") failed");
                }
                MemorySegment view = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(mapping, FILE_MAP_ALL_ACCESS, 0, 0, size);
                if (view.address() == 0L) {
                    int ignored = (int) CLOSE_HANDLE.invokeExact(mapping);
                    throw new IllegalStateException("MapViewOfFile(" + name + ") failed");
                }
                return new SharedMemory(name, view.reinterpret(size), mapping.address(), true);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        static @Nullable SharedMemory openReadOnly(String name, long size) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mapping = (MemorySegment) OPEN_FILE_MAPPING.invokeExact(FILE_MAP_READ, 0, wide(arena, name));
                if (mapping.address() == 0L) {
                    return null;
                }
                // Fails if the block is smaller than size.
                MemorySegment view = (MemorySegment) MAP_VIEW_OF_FILE.invokeExact(mapping, FILE_MAP_READ, 0, 0, size);
                if (view.address() == 0L) {
                    int ignored = (int) CLOSE_HANDLE.invokeExact(mapping);
                    return null;
                }
                return new SharedMemory(name, view.reinterpret(size), mapping.address(), false);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        static void close(SharedMemory memory) {
            try {
                int ignored = (int) UNMAP_VIEW_OF_FILE.invokeExact(memory.segment);
                ignored = (int) CLOSE_HANDLE.invokeExact(MemorySegment.ofAddress(memory.handle));
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }
    }
}
