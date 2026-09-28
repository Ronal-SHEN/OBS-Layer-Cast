#include "lc-texture.h"

bool lc_transport_supported(uint32_t transport)
{
	return transport == LC_TRANSPORT_SHM;
}

gs_texture_t *lc_open_shared_texture(const struct lc_layer_config *cfg, uint32_t slot, uint64_t producer_pid)
{
	(void)cfg;
	(void)slot;
	(void)producer_pid;
	return NULL;
}
