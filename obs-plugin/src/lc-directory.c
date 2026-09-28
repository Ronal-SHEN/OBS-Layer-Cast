#include "lc-directory.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <sddl.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#endif

#ifdef _WIN32
static wchar_t *utf8_to_wide(const char *s)
{
	int len = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
	if (len <= 0)
		return NULL;
	wchar_t *w = (wchar_t *)malloc(sizeof(wchar_t) * (size_t)len);
	if (w)
		MultiByteToWideChar(CP_UTF8, 0, s, -1, w, len);
	return w;
}

bool lc_mapping_open(struct lc_mapping *map, const char *name, size_t min_size)
{
	memset(map, 0, sizeof(*map));
	wchar_t *wname = utf8_to_wide(name);
	if (!wname)
		return false;
	HANDLE handle = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, wname);
	free(wname);
	if (!handle)
		return false;
	void *view = MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, 0);
	if (!view) {
		CloseHandle(handle);
		return false;
	}
	MEMORY_BASIC_INFORMATION info;
	if (VirtualQuery(view, &info, sizeof(info)) == 0 || info.RegionSize < min_size) {
		UnmapViewOfFile(view);
		CloseHandle(handle);
		return false;
	}
	map->base = view;
	map->size = info.RegionSize;
	map->handle = handle;
	return true;
}

bool lc_mapping_create(struct lc_mapping *map, const char *name, size_t size)
{
	memset(map, 0, sizeof(*map));
	wchar_t *wname = utf8_to_wide(name);
	if (!wname)
		return false;
	/* OBS may run as administrator while the game does not: let the owner, SYSTEM and administrators do anything
	 * and interactive users read. Without a descriptor an elevated OBS would create a block the game cannot open. */
	SECURITY_ATTRIBUTES sa = {sizeof(sa), NULL, FALSE};
	PSECURITY_DESCRIPTOR sd = NULL;
	if (ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:(A;;GA;;;OW)(A;;GA;;;SY)(A;;GA;;;BA)(A;;GR;;;IU)",
								 SDDL_REVISION_1, &sd, NULL))
		sa.lpSecurityDescriptor = sd;
	HANDLE handle = CreateFileMappingW(INVALID_HANDLE_VALUE, sd ? &sa : NULL, PAGE_READWRITE, 0, (DWORD)size, wname);
	if (sd)
		LocalFree(sd);
	free(wname);
	if (!handle)
		return false;
	void *view = MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, size);
	if (!view) {
		CloseHandle(handle);
		return false;
	}
	map->base = view;
	map->size = size;
	map->handle = handle;
	return true;
}

void lc_presence_name(char *buf, size_t size)
{
	snprintf(buf, size, "Local\\LayerCast.v1.obs.plugin");
}

void lc_mapping_close(struct lc_mapping *map)
{
	if (map->base)
		UnmapViewOfFile(map->base);
	if (map->handle)
		CloseHandle((HANDLE)map->handle);
	memset(map, 0, sizeof(*map));
}

uint64_t lc_now_ms(void)
{
	FILETIME ft;
	GetSystemTimeAsFileTime(&ft);
	uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
	return (t - 116444736000000000ULL) / 10000ULL;
}

void lc_directory_name(char *buf, size_t size, const char *channel)
{
	snprintf(buf, size, "Local\\LayerCast.v1.%s", channel);
}

void lc_shm_frame_name(char *buf, size_t size, const char *channel, uint32_t layer_index, uint32_t generation)
{
	snprintf(buf, size, "Local\\LayerCast.v1.%s.%u.%u", channel, layer_index, generation);
}
#else
bool lc_mapping_open(struct lc_mapping *map, const char *name, size_t min_size)
{
	memset(map, 0, sizeof(*map));
	int fd = shm_open(name, O_RDWR, 0);
	if (fd < 0)
		return false;
	struct stat st;
	if (fstat(fd, &st) != 0 || (size_t)st.st_size < min_size) {
		close(fd);
		return false;
	}
	size_t size = (size_t)st.st_size;
	void *base = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	close(fd);
	if (base == MAP_FAILED)
		return false;
	map->base = base;
	map->size = size;
	return true;
}

bool lc_mapping_create(struct lc_mapping *map, const char *name, size_t size)
{
	memset(map, 0, sizeof(*map));
	int fd = shm_open(name, O_RDWR | O_CREAT, 0644);
	if (fd < 0)
		return false;
	/* macOS only allows sizing a shm object once; if it exists, it must be large enough already. */
	struct stat st;
	if ((ftruncate(fd, (off_t)size) != 0 && (fstat(fd, &st) != 0 || (size_t)st.st_size < size))) {
		close(fd);
		return false;
	}
	void *base = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	close(fd);
	if (base == MAP_FAILED)
		return false;
	map->base = base;
	map->size = size;
	return true;
}

void lc_presence_name(char *buf, size_t size)
{
	snprintf(buf, size, "/layercast.v1.obs.plugin");
}

void lc_mapping_close(struct lc_mapping *map)
{
	if (map->base)
		munmap(map->base, map->size);
	memset(map, 0, sizeof(*map));
}

uint64_t lc_now_ms(void)
{
	struct timeval tv;
	gettimeofday(&tv, NULL);
	return (uint64_t)tv.tv_sec * 1000ULL + (uint64_t)tv.tv_usec / 1000ULL;
}

void lc_directory_name(char *buf, size_t size, const char *channel)
{
	snprintf(buf, size, "/layercast.v1.%s", channel);
}

void lc_shm_frame_name(char *buf, size_t size, const char *channel, uint32_t layer_index, uint32_t generation)
{
	snprintf(buf, size, "/lc1.%s.%u.%u", channel, layer_index, generation);
}
#endif

void lc_sanitize_channel(char *out, size_t size, const char *channel)
{
	size_t n = 0;
	for (const char *c = channel ? channel : ""; *c && n + 1 < size && n < LC_CHANNEL_MAX_LEN; c++) {
		char ch = *c;
		if (ch >= 'A' && ch <= 'Z')
			ch = (char)(ch + 32);
		if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')
			out[n++] = ch;
	}
	if (n == 0) {
		snprintf(out, size, "default");
		return;
	}
	out[n] = '\0';
}

bool lc_directory_valid(const struct lc_mapping *map)
{
	if (!map->base || map->size < LC_DIRECTORY_SIZE)
		return false;
	const struct lc_header *h = lc_header_of(map);
	return lc_load32(&h->magic) == LC_MAGIC && h->version == LC_VERSION && h->header_size == LC_HEADER_SIZE &&
	       h->layer_stride == LC_LAYER_STRIDE && h->max_layers == LC_MAX_LAYERS && h->layer_count <= LC_MAX_LAYERS;
}

bool lc_producer_alive(const struct lc_mapping *map)
{
	const struct lc_header *h = lc_header_of(map);
	uint64_t heartbeat = lc_load64(&h->producer_heartbeat_ms);
	return heartbeat != 0 && lc_now_ms() <= heartbeat + LC_PRODUCER_TIMEOUT_MS;
}

int lc_find_layer(const struct lc_mapping *map, const char *id)
{
	const struct lc_header *h = lc_header_of(map);
	uint32_t count = h->layer_count;
	if (count > LC_MAX_LAYERS)
		count = LC_MAX_LAYERS;
	for (uint32_t i = 0; i < count; i++) {
		const struct lc_layer *layer = lc_layer_at(map, i);
		char layer_id[LC_ID_LEN + 1];
		memcpy(layer_id, layer->id, LC_ID_LEN);
		layer_id[LC_ID_LEN] = '\0';
		if (strcmp(layer_id, id) == 0)
			return (int)i;
	}
	return -1;
}

bool lc_read_layer_config(const struct lc_layer *layer, struct lc_layer_config *out)
{
	uint32_t gen = lc_load32(&layer->generation);
	if (gen & 1u)
		return false;
	lc_fence();
	out->state = layer->state;
	out->flags = layer->flags;
	out->width = layer->width;
	out->height = layer->height;
	out->format = layer->format;
	out->transport = layer->transport;
	out->slot_count = layer->slot_count;
	for (uint32_t i = 0; i < LC_MAX_SLOTS; i++)
		out->slot_handles[i] = layer->slot_handles[i];
	lc_fence();
	if (lc_load32(&layer->generation) != gen)
		return false;
	out->generation = gen;
	if (out->slot_count > LC_MAX_SLOTS)
		out->slot_count = LC_MAX_SLOTS;
	return true;
}
