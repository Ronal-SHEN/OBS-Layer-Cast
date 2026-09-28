package starship.layercast.share.transport;

import starship.layercast.layer.Layer;
import starship.layercast.share.Protocol;
import starship.layercast.share.ffm.Native;

/**
 * Picks the transport for the running OS and render backend.
 */
public final class Transports {
    private Transports() {
    }

    /** Whether this OS has a zero-copy GPU transport for the render backend at all (Linux: not yet). */
    public static boolean gpuSupported(int backend) {
        boolean os = Native.OS == Native.Os.MACOS || Native.OS == Native.Os.WINDOWS;
        //? if >=26.2 {
        return os && (backend == Protocol.BACKEND_OPENGL || backend == Protocol.BACKEND_VULKAN);
        //?} else
        /*return os && backend == Protocol.BACKEND_OPENGL;*/
    }

    /** Creates the zero-copy GPU transport for this platform, or throws if there is none. */
    public static Transport createGpu(int backend, Layer layer) {
        if (backend == Protocol.BACKEND_OPENGL) {
            switch (Native.OS) {
                case MACOS:
                    return new MacGlIOSurfaceTransport();
                case WINDOWS:
                    return new WinGlDxInteropTransport();
                default:
                    break;
            }
        }
        //? if >=26.2 {
        if (backend == Protocol.BACKEND_VULKAN) {
            switch (Native.OS) {
                case MACOS:
                    return new MacVkIOSurfaceTransport();
                case WINDOWS:
                    return new WinVkD3D11Transport();
                default:
                    break;
            }
        }
        //?}
        throw new UnsupportedOperationException("No GPU transport for " + Native.OS + " with backend " + backend);
    }

    public static Transport createCpu() {
        return new CpuTransport();
    }
}
