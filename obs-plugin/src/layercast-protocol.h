/*
 * OBS Layer Cast shared-memory directory protocol (v1).
 *
 * This header is the single source of truth for the layout; the Java side mirrors it in
 * mod/src/main/java/starship/layercast/share/Protocol.java. Keep both in sync.
 *
 * The directory is a named shared-memory block:
 *   Windows : "Local\LayerCast.v1.<channel>"   (CreateFileMappingW, pagefile backed)
 *   POSIX   : "/layercast.v1.<channel>"          (shm_open)
 * All integers are little-endian and naturally aligned.
 *
 * Producer (Minecraft) owns every field except the consumer area of each layer.
 * Consumers (OBS sources) only write the consumer area.
 *
 * Consistency rules
 *  - A layer's configuration (size, format, transport, handles) is guarded by `generation`,
 *    used as a seqlock: odd while the producer rewrites it, even when stable. Readers copy the
 *    configuration and accept it only if `generation` is even and unchanged afterwards.
 *  - Frames are published with a single 64-bit store to `published`
 *    ((frame_number << 8) | slot_index). 0 means "nothing published yet".
 *  - Liveness is detected with wall-clock millisecond heartbeats written by each side.
 */
#pragma once

#include <stdint.h>

#define LC_MAGIC 0x5453434Cu /* "LCST" */
#define LC_VERSION 1u
#define LC_HEADER_SIZE 256u
#define LC_LAYER_STRIDE 512u
#define LC_MAX_LAYERS 32u
#define LC_MAX_SLOTS 4u
#define LC_ID_LEN 32u
#define LC_NAME_LEN 64u
#define LC_DIRECTORY_SIZE (LC_HEADER_SIZE + LC_LAYER_STRIDE * LC_MAX_LAYERS)
#define LC_CHANNEL_MAX_LEN 16u

/* Producer heartbeat older than this means Minecraft is gone or frozen. */
#define LC_PRODUCER_TIMEOUT_MS 3000u
/* Consumer heartbeat older than this means nobody in OBS shows the layer (consumer_attached_ms: nobody reads it). */
#define LC_CONSUMER_TIMEOUT_MS 2000u

enum lc_backend {
	LC_BACKEND_UNKNOWN = 0,
	LC_BACKEND_OPENGL = 1,
	LC_BACKEND_VULKAN = 2,
};

enum lc_layer_state {
	LC_STATE_EMPTY = 0,  /* descriptor unused */
	LC_STATE_IDLE = 1,   /* layer known, no frames (not requested or not yet produced) */
	LC_STATE_ACTIVE = 2, /* frames are being published */
	LC_STATE_ERROR = 3,  /* producer could not share this layer */
};

enum lc_layer_flags {
	LC_FLAG_FLIP_Y = 1u << 0,        /* row 0 of the shared image is the bottom of the picture */
	LC_FLAG_OPAQUE = 1u << 1,        /* alpha must be ignored */
	LC_FLAG_PREMULTIPLIED = 1u << 2, /* colour is premultiplied by alpha */
};

enum lc_format {
	LC_FORMAT_NONE = 0,
	LC_FORMAT_RGBA8 = 1,
	LC_FORMAT_BGRA8 = 2,
};

enum lc_transport {
	LC_TRANSPORT_NONE = 0,
	LC_TRANSPORT_D3D11_KMT = 1, /* handle: legacy global D3D11 shared handle */
	LC_TRANSPORT_D3D11_NT = 2,  /* handle: NT handle in the producer process, DuplicateHandle it */
	LC_TRANSPORT_IOSURFACE = 3, /* handle: global IOSurfaceID */
	LC_TRANSPORT_DMABUF = 4,    /* reserved */
	LC_TRANSPORT_SHM = 5,       /* CPU frames in a separate shared-memory block, see lc_shm_frame_header */
};

enum lc_consumer_error {
	LC_CONSUMER_OK = 0,
	LC_CONSUMER_OPEN_FAILED = 1, /* shared handles could not be opened (e.g. different GPU) */
};

#pragma pack(push, 1)

struct lc_header {
	uint32_t magic;                 /*   0 */
	uint32_t version;               /*   4 */
	uint32_t header_size;           /*   8 */
	uint32_t layer_stride;          /*  12 */
	uint32_t max_layers;            /*  16 */
	uint32_t layer_count;           /*  20 */
	uint64_t session_id;            /*  24 random per producer start */
	uint64_t producer_pid;          /*  32 */
	uint64_t producer_heartbeat_ms; /*  40 wall clock */
	uint32_t backend;               /*  48 enum lc_backend */
	uint32_t directory_seq;         /*  52 bumped when the layer list changes */
	char producer_name[64];         /*  56 UTF-8, NUL terminated */
	uint8_t reserved[136];          /* 120 */
};

struct lc_layer {
	/* producer area */
	char id[LC_ID_LEN];             /*   0 */
	char name[LC_NAME_LEN];         /*  32 */
	uint32_t state;                 /*  96 enum lc_layer_state */
	uint32_t flags;                 /* 100 enum lc_layer_flags */
	uint32_t width;                 /* 104 */
	uint32_t height;                /* 108 */
	uint32_t format;                /* 112 enum lc_format */
	uint32_t transport;             /* 116 enum lc_transport */
	uint32_t slot_count;            /* 120 <= LC_MAX_SLOTS */
	uint32_t generation;            /* 124 seqlock, see above */
	uint64_t slot_handles[LC_MAX_SLOTS]; /* 128 */
	uint64_t published;             /* 160 (frame << 8) | slot */
	uint64_t publish_time_ms;       /* 168 */
	uint32_t target_fps;            /* 176 */
	uint8_t producer_reserved[76];  /* 180 */
	/* consumer area */
	uint64_t consumer_heartbeat_ms; /* 256 */
	uint32_t consumer_reading_slot; /* 264 0xFFFFFFFF = none */
	uint32_t consumer_error;        /* 268 enum lc_consumer_error */
	uint32_t consumer_error_generation; /* 272 generation the error refers to */
	uint32_t consumer_fps;          /* 276 OBS output frame rate */
	uint64_t consumer_attached_ms;  /* 280 written by every source reading the layer, visible or not */
	uint8_t consumer_reserved[224]; /* 288 */
};

/*
 * LC_TRANSPORT_SHM: each layer generation gets its own block
 *   Windows "Local\LayerCast.v1.<channel>.<layer index>.<generation>"
 *   POSIX   "/lc1.<channel>.<layer index>.<generation>"
 * laid out as a header followed by slot_count tightly packed frames of width*height*4 bytes
 * starting at LC_SHM_DATA_OFFSET. frame_seq[i] is odd while slot i is being written.
 */
#define LC_SHM_DATA_OFFSET 256u
struct lc_shm_frame_header {
	uint32_t magic;           /* LC_MAGIC */
	uint32_t width;
	uint32_t height;
	uint32_t stride;          /* bytes per row */
	uint64_t frame_seq[LC_MAX_SLOTS];
	uint8_t reserved[208];
};

/*
 * OBS presence block: created by the plugin when OBS loads it and kept fresh by a heartbeat while OBS runs, so the
 * game can tell that the plugin is running before any source reads it.
 *   Windows "Local\LayerCast.v1.obs.plugin", POSIX "/layercast.v1.obs.plugin" (channels cannot contain '.').
 * Several OBS instances share it (last writer wins). It is not removed when OBS exits, since another OBS may still
 * use it: a heartbeat older than LC_PRESENCE_TIMEOUT_MS means no plugin is running.
 */
#define LC_PRESENCE_SIZE 64u
#define LC_PRESENCE_TIMEOUT_MS 3000u

struct lc_presence {
	uint32_t magic;        /*  0 LC_MAGIC once initialised */
	uint32_t version;      /*  4 LC_VERSION */
	uint64_t obs_pid;      /*  8 */
	uint64_t heartbeat_ms; /* 16 wall clock, 0 after OBS exited cleanly */
	uint8_t reserved[40];  /* 24 */
};

#pragma pack(pop)

#ifdef __cplusplus
static_assert(sizeof(struct lc_header) == LC_HEADER_SIZE, "header size");
static_assert(sizeof(struct lc_layer) == LC_LAYER_STRIDE, "layer size");
static_assert(sizeof(struct lc_shm_frame_header) == LC_SHM_DATA_OFFSET, "shm header size");
static_assert(sizeof(struct lc_presence) == LC_PRESENCE_SIZE, "presence size");
#else
_Static_assert(sizeof(struct lc_header) == LC_HEADER_SIZE, "header size");
_Static_assert(sizeof(struct lc_layer) == LC_LAYER_STRIDE, "layer size");
_Static_assert(sizeof(struct lc_shm_frame_header) == LC_SHM_DATA_OFFSET, "shm header size");
_Static_assert(sizeof(struct lc_presence) == LC_PRESENCE_SIZE, "presence size");
#endif

static inline uint32_t lc_published_slot(uint64_t published)
{
	return (uint32_t)(published & 0xFFu);
}

static inline uint64_t lc_published_frame(uint64_t published)
{
	return published >> 8;
}
