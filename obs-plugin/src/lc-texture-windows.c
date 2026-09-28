#include "lc-texture.h"

#include <obs-module.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

bool lc_transport_supported(uint32_t transport)
{
	return transport == LC_TRANSPORT_D3D11_KMT || transport == LC_TRANSPORT_D3D11_NT || transport == LC_TRANSPORT_SHM;
}

gs_texture_t *lc_open_shared_texture(const struct lc_layer_config *cfg, uint32_t slot, uint64_t producer_pid)
{
	if (slot >= cfg->slot_count)
		return NULL;
	uint64_t handle = cfg->slot_handles[slot];
	if (!handle)
		return NULL;

	if (cfg->transport == LC_TRANSPORT_D3D11_KMT)
		return gs_texture_open_shared((uint32_t)handle);

	if (cfg->transport == LC_TRANSPORT_D3D11_NT) {
		HANDLE producer = OpenProcess(PROCESS_DUP_HANDLE, FALSE, (DWORD)producer_pid);
		if (!producer) {
			blog(LOG_WARNING, "[layercast] OpenProcess(%llu) failed: %lu", (unsigned long long)producer_pid,
			     GetLastError());
			return NULL;
		}
		HANDLE local = NULL;
		BOOL ok = DuplicateHandle(producer, (HANDLE)(uintptr_t)handle, GetCurrentProcess(), &local, 0, FALSE,
					  DUPLICATE_SAME_ACCESS);
		CloseHandle(producer);
		if (!ok || !local) {
			blog(LOG_WARNING, "[layercast] DuplicateHandle failed: %lu", GetLastError());
			return NULL;
		}
		gs_texture_t *tex = gs_texture_open_nt_shared((uint32_t)(uintptr_t)local);
		CloseHandle(local);
		return tex;
	}
	return NULL;
}
