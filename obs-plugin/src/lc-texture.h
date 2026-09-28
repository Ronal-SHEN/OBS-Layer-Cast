/*
 * Opening producer-shared GPU surfaces as OBS textures.
 */
#pragma once

#include <graphics/graphics.h>

#include "lc-directory.h"

/* Whether this build can open surfaces of the given transport at all. */
bool lc_transport_supported(uint32_t transport);

/*
 * Opens the shared surface of one slot. Must be called inside obs_enter_graphics().
 * Returns NULL (and logs) if the handle cannot be opened, e.g. because it lives on another GPU.
 */
gs_texture_t *lc_open_shared_texture(const struct lc_layer_config *cfg, uint32_t slot, uint64_t producer_pid);
