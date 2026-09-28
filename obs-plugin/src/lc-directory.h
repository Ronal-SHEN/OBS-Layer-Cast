/*
 * Consumer-side access to the LayerCast shared directory.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "layercast-protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

struct lc_mapping {
	void *base;
	size_t size;
	void *handle; /* Windows mapping handle */
};

/* Opens an existing named shared-memory block for reading and writing. */
bool lc_mapping_open(struct lc_mapping *map, const char *name, size_t min_size);
void lc_mapping_close(struct lc_mapping *map);
/* Creates a named shared-memory block (or opens it if it exists) that other users' processes may read. */
bool lc_mapping_create(struct lc_mapping *map, const char *name, size_t size);
/* Builds the OS object name of the OBS presence block into buf. */
void lc_presence_name(char *buf, size_t size);

/* Wall-clock milliseconds since the Unix epoch (same clock as Java's System.currentTimeMillis). */
uint64_t lc_now_ms(void);

/* Builds the OS object name of the directory for a channel into buf. */
void lc_directory_name(char *buf, size_t size, const char *channel);
/* Builds the OS object name of a CPU frame block. */
void lc_shm_frame_name(char *buf, size_t size, const char *channel, uint32_t layer_index, uint32_t generation);

/* Copies channel into out, keeping only [a-z0-9_-] (lower-cased), like the producer does. */
void lc_sanitize_channel(char *out, size_t size, const char *channel);

static inline struct lc_header *lc_header_of(const struct lc_mapping *map)
{
	return (struct lc_header *)map->base;
}

static inline struct lc_layer *lc_layer_at(const struct lc_mapping *map, uint32_t index)
{
	return (struct lc_layer *)((uint8_t *)map->base + LC_HEADER_SIZE + (size_t)index * LC_LAYER_STRIDE);
}

/* ---- atomics (the directory is written by another process) ---- */

#if defined(_MSC_VER)
#include <windows.h>
#include <intrin.h>
static inline uint64_t lc_load64(const volatile uint64_t *p)
{
	/* Aligned 64-bit loads are atomic on x64/ARM64; the barrier orders subsequent reads. */
	uint64_t v = *p;
	_ReadWriteBarrier();
	return v;
}
static inline uint32_t lc_load32(const volatile uint32_t *p)
{
	uint32_t v = *p;
	_ReadWriteBarrier();
	return v;
}
static inline void lc_store64(volatile uint64_t *p, uint64_t v)
{
	_ReadWriteBarrier();
	*p = v;
}
static inline void lc_store32(volatile uint32_t *p, uint32_t v)
{
	_ReadWriteBarrier();
	*p = v;
}
static inline void lc_fence(void)
{
	MemoryBarrier();
}
#else
static inline uint64_t lc_load64(const volatile uint64_t *p)
{
	return __atomic_load_n(p, __ATOMIC_ACQUIRE);
}
static inline uint32_t lc_load32(const volatile uint32_t *p)
{
	return __atomic_load_n(p, __ATOMIC_ACQUIRE);
}
static inline void lc_store64(volatile uint64_t *p, uint64_t v)
{
	__atomic_store_n(p, v, __ATOMIC_RELEASE);
}
static inline void lc_store32(volatile uint32_t *p, uint32_t v)
{
	__atomic_store_n(p, v, __ATOMIC_RELEASE);
}
static inline void lc_fence(void)
{
	__atomic_thread_fence(__ATOMIC_SEQ_CST);
}
#endif

/* Snapshot of a layer configuration taken under the generation seqlock. */
struct lc_layer_config {
	uint32_t state;
	uint32_t flags;
	uint32_t width;
	uint32_t height;
	uint32_t format;
	uint32_t transport;
	uint32_t slot_count;
	uint32_t generation;
	uint64_t slot_handles[LC_MAX_SLOTS];
};

/* Returns false while the producer is rewriting the configuration; try again next tick. */
bool lc_read_layer_config(const struct lc_layer *layer, struct lc_layer_config *out);

/* Validates magic, version and layout of a mapped directory. */
bool lc_directory_valid(const struct lc_mapping *map);

/* Finds a layer descriptor by id, returns -1 if not present. */
int lc_find_layer(const struct lc_mapping *map, const char *id);
/* The producer wrote a heartbeat recently (Minecraft is running and not frozen). */
bool lc_producer_alive(const struct lc_mapping *map);

#ifdef __cplusplus
}
#endif
