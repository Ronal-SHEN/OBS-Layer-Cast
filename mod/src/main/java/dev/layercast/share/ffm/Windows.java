package dev.layercast.share.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HexFormat;

import static dev.layercast.share.ffm.Native.C_INT;
import static dev.layercast.share.ffm.Native.C_POINTER;

/**
 * DXGI / Direct3D 11 / WGL_NV_DX_interop bindings. COM methods are called through their vtable slots; the
 * indices below follow the declaration order in the Windows SDK headers (d3d11.h, dxgi.h).
 */
public final class Windows {
    public static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
    public static final int D3D11_BIND_SHADER_RESOURCE = 0x8;
    public static final int D3D11_BIND_RENDER_TARGET = 0x20;
    public static final int D3D11_RESOURCE_MISC_SHARED = 0x2;
    public static final int D3D11_RESOURCE_MISC_SHARED_NTHANDLE = 0x800;
    public static final int DXGI_SHARED_RESOURCE_READ = 0x80000000;
    public static final int DXGI_SHARED_RESOURCE_WRITE = 0x1;
    public static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    public static final int D3D11_SDK_VERSION = 7;
    public static final int D3D_DRIVER_TYPE_UNKNOWN = 0;
    public static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    public static final int WGL_ACCESS_WRITE_DISCARD_NV = 0x2;

    // IUnknown
    private static final int QUERY_INTERFACE = 0;
    private static final int RELEASE = 2;
    // IDXGIFactory1
    private static final int FACTORY1_ENUM_ADAPTERS1 = 12;
    // IDXGIAdapter1
    private static final int ADAPTER1_GET_DESC1 = 10;
    // ID3D11Device
    private static final int DEVICE_CREATE_TEXTURE_2D = 5;
    // ID3D11DeviceContext
    private static final int CONTEXT_FLUSH = 111;
    // IDXGIResource
    private static final int RESOURCE_GET_SHARED_HANDLE = 8;
    // IDXGIResource1
    private static final int RESOURCE1_CREATE_SHARED_HANDLE = 13;

    private static final int DXGI_ADAPTER_DESC1_SIZE = 312;
    private static final int DXGI_ADAPTER_DESC1_LUID_OFFSET = 296;
    private static final int DXGI_ADAPTER_DESC1_FLAGS_OFFSET = 304;
    private static final int DXGI_ADAPTER_FLAG_SOFTWARE = 2;

    public static final byte[] IID_IDXGIFactory1 = guid("770aae78-f26f-4dba-a829-253c83d1b387");
    public static final byte[] IID_IDXGIResource = guid("035f3ab4-482e-4e50-b41f-8a7f8bd8960b");
    public static final byte[] IID_IDXGIResource1 = guid("30961379-4609-4a41-998e-54fe567ee0c1");

    private static final MethodHandle CREATE_DXGI_FACTORY1;
    private static final MethodHandle D3D11_CREATE_DEVICE;
    private static final MethodHandle WGL_GET_PROC_ADDRESS;

    private static final FunctionDescriptor FD_RELEASE = FunctionDescriptor.of(C_INT, C_POINTER);
    private static final FunctionDescriptor FD_QUERY_INTERFACE = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER);
    private static final FunctionDescriptor FD_ENUM_ADAPTERS1 = FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER);
    private static final FunctionDescriptor FD_GET_DESC1 = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER);
    private static final FunctionDescriptor FD_CREATE_TEXTURE_2D = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER);
    private static final FunctionDescriptor FD_FLUSH = FunctionDescriptor.ofVoid(C_POINTER);
    private static final FunctionDescriptor FD_GET_SHARED_HANDLE = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER);
    private static final FunctionDescriptor FD_CREATE_SHARED_HANDLE = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT, C_POINTER, C_POINTER);
    private static final MethodHandle CLOSE_HANDLE;

    static {
        SymbolLookup dxgi = Native.library("dxgi");
        SymbolLookup d3d11 = Native.library("d3d11");
        SymbolLookup opengl32 = Native.library("opengl32");
        CREATE_DXGI_FACTORY1 = Native.downcall(dxgi, "CreateDXGIFactory1", FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER));
        D3D11_CREATE_DEVICE = Native.downcall(d3d11, "D3D11CreateDevice",
            FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER, C_INT, C_POINTER, C_INT, C_INT, C_POINTER, C_POINTER, C_POINTER));
        WGL_GET_PROC_ADDRESS = Native.downcall(opengl32, "wglGetProcAddress", FunctionDescriptor.of(C_POINTER, C_POINTER));
        CLOSE_HANDLE = Native.downcall(Native.library("kernel32"), "CloseHandle", FunctionDescriptor.of(C_INT, C_POINTER));
    }

    private Windows() {
    }

    public static void load() {
    }

    static byte[] guid(String text) {
        byte[] raw = HexFormat.of().parseHex(text.replace("-", ""));
        byte[] out = new byte[16];
        // Data1 (LE u32), Data2 (LE u16), Data3 (LE u16), Data4 (bytes)
        out[0] = raw[3]; out[1] = raw[2]; out[2] = raw[1]; out[3] = raw[0];
        out[4] = raw[5]; out[5] = raw[4];
        out[6] = raw[7]; out[7] = raw[6];
        System.arraycopy(raw, 8, out, 8, 8);
        return out;
    }

    public static boolean failed(int hr) {
        return hr < 0;
    }

    public static String hr(int hr) {
        return String.format("0x%08X", hr);
    }

    /** Resolves {@code index} in the vtable of COM object {@code obj}. */
    static MethodHandle vtable(MemorySegment obj, int index, FunctionDescriptor descriptor) {
        MemorySegment vtbl = obj.reinterpret(8).get(C_POINTER, 0);
        MemorySegment fn = vtbl.reinterpret((index + 1) * 8L).get(C_POINTER, index * 8L);
        return Native.downcall(fn, descriptor);
    }

    public static void release(MemorySegment obj) {
        if (obj == null || obj.address() == 0L) {
            return;
        }
        try {
            int ignored = (int) vtable(obj, RELEASE, FD_RELEASE).invokeExact(obj);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    public static MemorySegment queryInterface(MemorySegment obj, byte[] iid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment riid = arena.allocateFrom(ValueLayout.JAVA_BYTE, iid);
            MemorySegment out = arena.allocate(C_POINTER);
            int hr = (int) vtable(obj, QUERY_INTERFACE, FD_QUERY_INTERFACE).invokeExact(obj, riid, out);
            if (failed(hr)) {
                throw new IllegalStateException("QueryInterface failed: " + hr(hr));
            }
            return out.get(C_POINTER, 0);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /**
     * Finds the DXGI adapter with the given LUID; returns NULL (default adapter) when none matches.
     * The caller releases a non-NULL result.
     */
    public static MemorySegment findAdapter(long luid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment riid = arena.allocateFrom(ValueLayout.JAVA_BYTE, IID_IDXGIFactory1);
            MemorySegment pFactory = arena.allocate(C_POINTER);
            int hr = (int) CREATE_DXGI_FACTORY1.invokeExact(riid, pFactory);
            if (failed(hr)) {
                throw new IllegalStateException("CreateDXGIFactory1 failed: " + hr(hr));
            }
            MemorySegment factory = pFactory.get(C_POINTER, 0);
            try {
                MethodHandle enumAdapters = vtable(factory, FACTORY1_ENUM_ADAPTERS1, FD_ENUM_ADAPTERS1);
                MemorySegment pAdapter = arena.allocate(C_POINTER);
                MemorySegment desc = arena.allocate(DXGI_ADAPTER_DESC1_SIZE, 8);
                for (int i = 0; ; i++) {
                    hr = (int) enumAdapters.invokeExact(factory, i, pAdapter);
                    if (failed(hr)) {
                        return MemorySegment.NULL; // DXGI_ERROR_NOT_FOUND: no more adapters
                    }
                    MemorySegment adapter = pAdapter.get(C_POINTER, 0);
                    hr = (int) vtable(adapter, ADAPTER1_GET_DESC1, FD_GET_DESC1).invokeExact(adapter, desc);
                    long adapterLuid = desc.get(ValueLayout.JAVA_LONG_UNALIGNED, DXGI_ADAPTER_DESC1_LUID_OFFSET);
                    int flags = desc.get(ValueLayout.JAVA_INT, DXGI_ADAPTER_DESC1_FLAGS_OFFSET);
                    if (!failed(hr) && adapterLuid == luid && (flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0) {
                        return adapter;
                    }
                    release(adapter);
                }
            } finally {
                release(factory);
            }
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    public record D3D11Device(MemorySegment device, MemorySegment context, int featureLevel) {
    }

    public static D3D11Device createDevice(MemorySegment adapter) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment levels = arena.allocateFrom(C_INT, 0xb100, 0xb000, 0xa100, 0xa000);
            MemorySegment pDevice = arena.allocate(C_POINTER);
            MemorySegment pLevel = arena.allocate(C_INT);
            MemorySegment pContext = arena.allocate(C_POINTER);
            boolean explicitAdapter = adapter.address() != 0L;
            int hr = (int) D3D11_CREATE_DEVICE.invokeExact(adapter,
                explicitAdapter ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, MemorySegment.NULL,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, 4, D3D11_SDK_VERSION, pDevice, pLevel, pContext);
            if (failed(hr)) {
                // Windows 7 era runtimes reject 11_1 in the list; retry without it.
                hr = (int) D3D11_CREATE_DEVICE.invokeExact(adapter,
                    explicitAdapter ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, MemorySegment.NULL,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels.asSlice(4), 3, D3D11_SDK_VERSION, pDevice, pLevel, pContext);
            }
            if (failed(hr)) {
                throw new IllegalStateException("D3D11CreateDevice failed: " + hr(hr));
            }
            return new D3D11Device(pDevice.get(C_POINTER, 0), pContext.get(C_POINTER, 0), pLevel.get(C_INT, 0));
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** Creates a shareable (legacy KMT handle) RGBA8 texture usable by OBS' {@code gs_texture_open_shared}. */
    public static MemorySegment createSharedTexture(MemorySegment device, int width, int height) {
        return createTexture(device, width, height, D3D11_RESOURCE_MISC_SHARED);
    }

    /** Creates an RGBA8 texture shareable through NT handles (importable into Vulkan, opened by OBS with gs_texture_open_nt_shared). */
    public static MemorySegment createNtSharedTexture(MemorySegment device, int width, int height) {
        return createTexture(device, width, height, D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE);
    }

    private static MemorySegment createTexture(MemorySegment device, int width, int height, int miscFlags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(44, 4);
            desc.set(C_INT, 0, width);
            desc.set(C_INT, 4, height);
            desc.set(C_INT, 8, 1);   // MipLevels
            desc.set(C_INT, 12, 1);  // ArraySize
            desc.set(C_INT, 16, DXGI_FORMAT_R8G8B8A8_UNORM);
            desc.set(C_INT, 20, 1);  // SampleDesc.Count
            desc.set(C_INT, 24, 0);  // SampleDesc.Quality
            desc.set(C_INT, 28, 0);  // D3D11_USAGE_DEFAULT
            desc.set(C_INT, 32, D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET);
            desc.set(C_INT, 36, 0);  // CPUAccessFlags
            desc.set(C_INT, 40, miscFlags);
            MemorySegment pTexture = arena.allocate(C_POINTER);
            int hr = (int) vtable(device, DEVICE_CREATE_TEXTURE_2D, FD_CREATE_TEXTURE_2D).invokeExact(device, desc, MemorySegment.NULL, pTexture);
            if (failed(hr)) {
                throw new IllegalStateException("CreateTexture2D(" + width + "x" + height + ") failed: " + hr(hr));
            }
            return pTexture.get(C_POINTER, 0);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** Returns the global (KMT) share handle of a texture created with {@code D3D11_RESOURCE_MISC_SHARED}. */
    public static long sharedHandle(MemorySegment texture) {
        MemorySegment resource = queryInterface(texture, IID_IDXGIResource);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pHandle = arena.allocate(C_POINTER);
            int hr = (int) vtable(resource, RESOURCE_GET_SHARED_HANDLE, FD_GET_SHARED_HANDLE).invokeExact(resource, pHandle);
            if (failed(hr)) {
                throw new IllegalStateException("GetSharedHandle failed: " + hr(hr));
            }
            return pHandle.get(C_POINTER, 0).address();
        } catch (Throwable t) {
            throw Native.rethrow(t);
        } finally {
            release(resource);
        }
    }

    /** Creates an NT handle (owned by the caller, close with {@link #closeHandle}) for an NT-shareable texture. */
    public static long createNtHandle(MemorySegment texture) {
        MemorySegment resource = queryInterface(texture, IID_IDXGIResource1);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pHandle = arena.allocate(C_POINTER);
            int hr = (int) vtable(resource, RESOURCE1_CREATE_SHARED_HANDLE, FD_CREATE_SHARED_HANDLE).invokeExact(resource,
                MemorySegment.NULL, DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE, MemorySegment.NULL, pHandle);
            if (failed(hr)) {
                throw new IllegalStateException("CreateSharedHandle failed: " + hr(hr));
            }
            return pHandle.get(C_POINTER, 0).address();
        } catch (Throwable t) {
            throw Native.rethrow(t);
        } finally {
            release(resource);
        }
    }

    public static void closeHandle(long handle) {
        if (handle == 0L) {
            return;
        }
        try {
            int ignored = (int) CLOSE_HANDLE.invokeExact(MemorySegment.ofAddress(handle));
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    public static MethodHandle contextFlush(MemorySegment context) {
        return vtable(context, CONTEXT_FLUSH, FD_FLUSH);
    }

    /** Looks up an OpenGL/WGL extension entry point of the current context. */
    public static MemorySegment wglProc(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fn = (MemorySegment) WGL_GET_PROC_ADDRESS.invokeExact(arena.allocateFrom(name));
            long address = fn.address();
            // wglGetProcAddress may return small sentinel values instead of NULL on failure.
            if (address == 0L || address == 1L || address == 2L || address == 3L || address == -1L) {
                return MemorySegment.NULL;
            }
            return fn;
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** WGL_NV_DX_interop2 entry points of the current GL context. */
    public static final class DxInterop {
        public final MethodHandle openDevice;
        public final MethodHandle closeDevice;
        public final MethodHandle registerObject;
        public final MethodHandle unregisterObject;
        public final MethodHandle lockObjects;
        public final MethodHandle unlockObjects;

        private DxInterop() {
            this.openDevice = Native.downcall(require("wglDXOpenDeviceNV"), FunctionDescriptor.of(C_POINTER, C_POINTER));
            this.closeDevice = Native.downcall(require("wglDXCloseDeviceNV"), FunctionDescriptor.of(C_INT, C_POINTER));
            this.registerObject = Native.downcall(require("wglDXRegisterObjectNV"),
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER, C_INT, C_INT, C_INT));
            this.unregisterObject = Native.downcall(require("wglDXUnregisterObjectNV"), FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER));
            this.lockObjects = Native.downcall(require("wglDXLockObjectsNV"), FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER));
            this.unlockObjects = Native.downcall(require("wglDXUnlockObjectsNV"), FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER));
        }

        private static MemorySegment require(String name) {
            MemorySegment fn = wglProc(name);
            if (fn.address() == 0L) {
                throw new UnsupportedOperationException(name + " is not available (WGL_NV_DX_interop2 unsupported by the GL driver)");
            }
            return fn;
        }

        /** Must be called with the Minecraft GL context current. */
        public static DxInterop load() {
            return new DxInterop();
        }
    }
}
