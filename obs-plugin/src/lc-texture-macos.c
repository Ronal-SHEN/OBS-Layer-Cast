#include "lc-texture.h"

#include <obs-module.h>
#include <IOSurface/IOSurface.h>

bool lc_transport_supported(uint32_t transport)
{
	return transport == LC_TRANSPORT_IOSURFACE || transport == LC_TRANSPORT_SHM;
}

gs_texture_t *lc_open_shared_texture(const struct lc_layer_config *cfg, uint32_t slot, uint64_t producer_pid)
{
	(void)producer_pid;
	if (cfg->transport != LC_TRANSPORT_IOSURFACE || slot >= cfg->slot_count)
		return NULL;

	IOSurfaceID id = (IOSurfaceID)cfg->slot_handles[slot];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
	/* Global IOSurfaces are deprecated but remain the only way to share without a mach-port broker. */
	IOSurfaceRef surface = IOSurfaceLookup(id);
#pragma clang diagnostic pop
	if (!surface) {
		blog(LOG_WARNING, "[layercast] IOSurfaceLookup(%u) failed", id);
		return NULL;
	}

	gs_texture_t *tex = NULL;
	if (IOSurfaceGetWidth(surface) == cfg->width && IOSurfaceGetHeight(surface) == cfg->height &&
	    IOSurfaceGetPixelFormat(surface) == 'BGRA') {
		tex = gs_texture_create_from_iosurface(surface);
	} else {
		blog(LOG_WARNING, "[layercast] IOSurface %u does not match the announced %ux%u BGRA layout", id, cfg->width,
		     cfg->height);
	}
	CFRelease(surface);
	return tex;
}
