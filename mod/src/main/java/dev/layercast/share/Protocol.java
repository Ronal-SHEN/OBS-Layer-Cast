package dev.layercast.share;

/**
 * Mirror of {@code obs-plugin/src/layercast-protocol.h}. Keep both files in sync.
 */
public final class Protocol {
    public static final int MAGIC = 0x5453434C; // "LCST"
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 256;
    public static final int LAYER_STRIDE = 512;
    public static final int MAX_LAYERS = 32;
    public static final int MAX_SLOTS = 4;
    public static final int ID_LEN = 32;
    public static final int NAME_LEN = 64;
    public static final int DIRECTORY_SIZE = HEADER_SIZE + LAYER_STRIDE * MAX_LAYERS;
    public static final int CHANNEL_MAX_LEN = 16;

    public static final long PRODUCER_TIMEOUT_MS = 3000;
    public static final long CONSUMER_TIMEOUT_MS = 2000;

    // lc_header offsets
    public static final long H_MAGIC = 0;
    public static final long H_VERSION = 4;
    public static final long H_HEADER_SIZE = 8;
    public static final long H_LAYER_STRIDE = 12;
    public static final long H_MAX_LAYERS = 16;
    public static final long H_LAYER_COUNT = 20;
    public static final long H_SESSION_ID = 24;
    public static final long H_PRODUCER_PID = 32;
    public static final long H_PRODUCER_HEARTBEAT = 40;
    public static final long H_BACKEND = 48;
    public static final long H_DIRECTORY_SEQ = 52;
    public static final long H_PRODUCER_NAME = 56;
    public static final int PRODUCER_NAME_LEN = 64;

    // lc_layer offsets (relative to the layer descriptor)
    public static final long L_ID = 0;
    public static final long L_NAME = 32;
    public static final long L_STATE = 96;
    public static final long L_FLAGS = 100;
    public static final long L_WIDTH = 104;
    public static final long L_HEIGHT = 108;
    public static final long L_FORMAT = 112;
    public static final long L_TRANSPORT = 116;
    public static final long L_SLOT_COUNT = 120;
    public static final long L_GENERATION = 124;
    public static final long L_SLOT_HANDLES = 128;
    public static final long L_PUBLISHED = 160;
    public static final long L_PUBLISH_TIME = 168;
    public static final long L_TARGET_FPS = 176;
    public static final long L_CONSUMER_HEARTBEAT = 256;
    public static final long L_CONSUMER_READING_SLOT = 264;
    public static final long L_CONSUMER_ERROR = 268;
    public static final long L_CONSUMER_ERROR_GENERATION = 272;
    public static final long L_CONSUMER_FPS = 276;
    public static final long L_CONSUMER_ATTACHED = 280;

    public static final int BACKEND_UNKNOWN = 0;
    public static final int BACKEND_OPENGL = 1;
    public static final int BACKEND_VULKAN = 2;

    public static final int STATE_EMPTY = 0;
    public static final int STATE_IDLE = 1;
    public static final int STATE_ACTIVE = 2;
    public static final int STATE_ERROR = 3;

    public static final int FLAG_FLIP_Y = 1;
    public static final int FLAG_OPAQUE = 1 << 1;
    public static final int FLAG_PREMULTIPLIED = 1 << 2;

    public static final int FORMAT_NONE = 0;
    public static final int FORMAT_RGBA8 = 1;
    public static final int FORMAT_BGRA8 = 2;

    public static final int TRANSPORT_NONE = 0;
    public static final int TRANSPORT_D3D11_KMT = 1;
    public static final int TRANSPORT_D3D11_NT = 2;
    public static final int TRANSPORT_IOSURFACE = 3;
    public static final int TRANSPORT_DMABUF = 4;
    public static final int TRANSPORT_SHM = 5;

    public static final int CONSUMER_OK = 0;
    public static final int CONSUMER_OPEN_FAILED = 1;

    // lc_presence (OBS presence block, written by the plugin)
    public static final int PRESENCE_SIZE = 64;
    public static final long PRESENCE_TIMEOUT_MS = 3000;
    public static final long P_MAGIC = 0;
    public static final long P_HEARTBEAT = 16;

    // lc_shm_frame_header
    public static final int SHM_DATA_OFFSET = 256;
    public static final long S_MAGIC = 0;
    public static final long S_WIDTH = 4;
    public static final long S_HEIGHT = 8;
    public static final long S_STRIDE = 12;
    public static final long S_FRAME_SEQ = 16;

    private Protocol() {
    }

    public static long packPublished(long frame, int slot) {
        return (frame << 8) | (slot & 0xFF);
    }

    public static String directoryName(String channel, boolean windows) {
        return windows ? "Local\\LayerCast.v1." + channel : "/layercast.v1." + channel;
    }

    /** Channels cannot contain '.', so this never clashes with a directory. */
    public static String presenceName(boolean windows) {
        return windows ? "Local\\LayerCast.v1.obs.plugin" : "/layercast.v1.obs.plugin";
    }

    public static String shmFrameName(String channel, int layerIndex, int generation, boolean windows) {
        return windows
            ? "Local\\LayerCast.v1." + channel + "." + layerIndex + "." + Integer.toUnsignedString(generation)
            : "/lc1." + channel + "." + layerIndex + "." + Integer.toUnsignedString(generation);
    }

    /** Channels end up in OS object names; keep them short and portable. */
    public static String sanitizeChannel(String channel) {
        StringBuilder sb = new StringBuilder();
        for (char c : channel.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            }
            if (sb.length() >= CHANNEL_MAX_LEN) {
                break;
            }
        }
        return sb.isEmpty() ? "default" : sb.toString();
    }
}
