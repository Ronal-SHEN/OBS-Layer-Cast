/*
 * OBS Layer Cast — receives HUD layers shared by the Minecraft mod through GPU texture sharing.
 */
#include <obs-module.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <unistd.h>
#endif

#include "lc-directory.h"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-layercast", "en-US")

extern struct obs_source_info layercast_source_info;

gs_effect_t *lc_effect_2d = NULL;
gs_effect_t *lc_effect_rect = NULL;

MODULE_EXPORT const char *obs_module_description(void)
{
	return "Shows Minecraft HUD layers shared by the OBS Layer Cast mod.";
}

/* Tells the game that the plugin runs (see struct lc_presence). */
static struct lc_mapping presence;
static uint64_t presence_written_ms;

static struct lc_presence *presence_block(void)
{
	return (struct lc_presence *)presence.base;
}

static void presence_tick(void *param, float seconds)
{
	(void)param;
	(void)seconds;
	uint64_t now = lc_now_ms();
	if (now - presence_written_ms < 250)
		return;
	presence_written_ms = now;
	struct lc_presence *p = presence_block();
	/* Another OBS may have taken the block over, or reset it after exiting: claim it again. */
	if (lc_load32(&p->magic) != LC_MAGIC) {
		p->version = LC_VERSION;
		lc_store32(&p->magic, LC_MAGIC);
	}
#ifdef _WIN32
	p->obs_pid = GetCurrentProcessId();
#else
	p->obs_pid = (uint64_t)getpid();
#endif
	lc_store64(&p->heartbeat_ms, now);
}

static void presence_start(void)
{
	char name[64];
	lc_presence_name(name, sizeof(name));
	if (!lc_mapping_create(&presence, name, LC_PRESENCE_SIZE)) {
		blog(LOG_WARNING, "[layercast] could not create %s; the game will not see the plugin until a source reads it", name);
		return;
	}
	presence_written_ms = 0;
	presence_tick(NULL, 0.0f);
	obs_add_tick_callback(presence_tick, NULL);
}

static void presence_stop(void)
{
	if (!presence.base)
		return;
	obs_remove_tick_callback(presence_tick, NULL);
	lc_store64(&presence_block()->heartbeat_ms, 0);
	lc_mapping_close(&presence);
}

static gs_effect_t *load_effect(const char *file)
{
	char *path = obs_module_file(file);
	if (!path) {
		blog(LOG_ERROR, "[layercast] missing %s", file);
		return NULL;
	}
	char *errors = NULL;
	gs_effect_t *effect = gs_effect_create_from_file(path, &errors);
	if (!effect)
		blog(LOG_ERROR, "[layercast] failed to compile %s: %s", file, errors ? errors : "(no details)");
	bfree(errors);
	bfree(path);
	return effect;
}

bool obs_module_load(void)
{
	obs_enter_graphics();
	lc_effect_2d = load_effect("effects/layercast.effect");
	/* GL rectangle textures (IOSurfaces under the macOS OpenGL renderer) need texture_rect sampling. */
	if (gs_get_device_type() == GS_DEVICE_OPENGL)
		lc_effect_rect = load_effect("effects/layercast_rect.effect");
	obs_leave_graphics();

	if (!lc_effect_2d)
		return false;

	obs_register_source(&layercast_source_info);
	presence_start();
	blog(LOG_INFO, "[layercast] loaded (protocol v%u)", LC_VERSION);
	return true;
}

void obs_module_unload(void)
{
	presence_stop();
	obs_enter_graphics();
	gs_effect_destroy(lc_effect_2d);
	gs_effect_destroy(lc_effect_rect);
	obs_leave_graphics();
	lc_effect_2d = NULL;
	lc_effect_rect = NULL;
}
