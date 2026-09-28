/*
 * "Minecraft Layer (LayerCast)" source: shows one layer published by the OBS Layer Cast Minecraft mod.
 *
 * Threading: create/destroy/update/properties run on the UI thread, video_tick/video_render on the graphics
 * thread. Everything touching the mapping or textures happens in video_tick/video_render; update() only stores
 * the new settings under a mutex and flags a reconnect.
 */
#include <obs-module.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
typedef SRWLOCK lc_mutex_t;
static bool lc_mutex_init(lc_mutex_t *m)
{
	InitializeSRWLock(m);
	return true;
}
static void lc_mutex_destroy(lc_mutex_t *m)
{
	(void)m;
}
static void lc_mutex_lock(lc_mutex_t *m)
{
	AcquireSRWLockExclusive(m);
}
static void lc_mutex_unlock(lc_mutex_t *m)
{
	ReleaseSRWLockExclusive(m);
}
#else
#include <pthread.h>
typedef pthread_mutex_t lc_mutex_t;
static bool lc_mutex_init(lc_mutex_t *m)
{
	return pthread_mutex_init(m, NULL) == 0;
}
static void lc_mutex_destroy(lc_mutex_t *m)
{
	pthread_mutex_destroy(m);
}
static void lc_mutex_lock(lc_mutex_t *m)
{
	pthread_mutex_lock(m);
}
static void lc_mutex_unlock(lc_mutex_t *m)
{
	pthread_mutex_unlock(m);
}
#endif

#include "layercast-protocol.h"
#include "lc-directory.h"
#include "lc-texture.h"

#define S_CHANNEL "channel"
#define S_LAYER "layer"
#define S_KEEP_ACTIVE "keep_active"
#define S_STATUS "status"

#define RECONNECT_INTERVAL_S 1.0f

extern gs_effect_t *lc_effect_2d;
extern gs_effect_t *lc_effect_rect;

struct builtin_layer {
	const char *id;
	const char *text_key;
};

/* Localized names of the built-in layers (mod layers use the name Minecraft publishes). */
static const struct builtin_layer builtin_layers[] = {
	{"game", "Layer.game"},
	{"hotbar", "Layer.hotbar"},       {"crosshair", "Layer.crosshair"},
	{"effects", "Layer.effects"},     {"bossbar", "Layer.bossbar"},
	{"scoreboard", "Layer.scoreboard"}, {"actionbar", "Layer.actionbar"},
	{"title", "Layer.title"},         {"chat", "Layer.chat"},
	{"tablist", "Layer.tablist"},     {"subtitles", "Layer.subtitles"},
	{"debug", "Layer.debug"},         {"screen", "Layer.screen"},
	{"toasts", "Layer.toasts"},       {"camera", "Layer.camera"},
	{"nametags", "Layer.nametags"},
};

struct lc_source {
	obs_source_t *source;

	lc_mutex_t settings_mutex;
	char channel[LC_CHANNEL_MAX_LEN + 1];
	char layer_id[LC_ID_LEN + 1];
	bool keep_active;
	volatile bool settings_changed;

	/* graphics thread state */
	struct lc_mapping map;
	char active_channel[LC_CHANNEL_MAX_LEN + 1];
	char active_layer[LC_ID_LEN + 1];
	bool active_keep;
	float reconnect_timer;
	uint64_t session_id;
	int layer_index;

	struct lc_layer_config cfg;
	bool cfg_valid;
	bool open_failed;
	gs_texture_t *textures[LC_MAX_SLOTS];

	/* LC_TRANSPORT_SHM */
	struct lc_mapping frames;
	gs_texture_t *upload_texture;
	uint64_t uploaded_frame;

	uint32_t draw_slot;
	bool has_frame;
	uint64_t last_frame;
	uint32_t width;
	uint32_t height;
};

static const char *lc_source_get_name(void *type_data)
{
	(void)type_data;
	return obs_module_text("LayerCastSource");
}

static void release_textures(struct lc_source *s)
{
	bool any = s->upload_texture != NULL;
	for (uint32_t i = 0; i < LC_MAX_SLOTS; i++)
		any = any || s->textures[i];
	if (any) {
		obs_enter_graphics();
		for (uint32_t i = 0; i < LC_MAX_SLOTS; i++) {
			gs_texture_destroy(s->textures[i]);
			s->textures[i] = NULL;
		}
		gs_texture_destroy(s->upload_texture);
		s->upload_texture = NULL;
		obs_leave_graphics();
	}
	lc_mapping_close(&s->frames);
	s->cfg_valid = false;
	s->open_failed = false;
	s->has_frame = false;
	s->uploaded_frame = 0;
}

static void disconnect(struct lc_source *s)
{
	release_textures(s);
	lc_mapping_close(&s->map);
	s->session_id = 0;
	s->layer_index = -1;
	s->reconnect_timer = 0.0f;
}

static void report_open_failure(struct lc_layer *layer, uint32_t generation)
{
	lc_store32(&layer->consumer_error_generation, generation);
	lc_store32(&layer->consumer_error, LC_CONSUMER_OPEN_FAILED);
}

static bool open_shm_frames(struct lc_source *s, const struct lc_layer_config *cfg)
{
	char name[128];
	lc_shm_frame_name(name, sizeof(name), s->active_channel, (uint32_t)s->layer_index, cfg->generation);
	size_t frame_bytes = (size_t)cfg->width * cfg->height * 4u;
	if (!lc_mapping_open(&s->frames, name, LC_SHM_DATA_OFFSET + frame_bytes * cfg->slot_count))
		return false;
	const struct lc_shm_frame_header *fh = (const struct lc_shm_frame_header *)s->frames.base;
	if (fh->magic != LC_MAGIC || fh->width != cfg->width || fh->height != cfg->height ||
	    fh->stride != cfg->width * 4u) {
		lc_mapping_close(&s->frames);
		return false;
	}
	enum gs_color_format fmt = cfg->format == LC_FORMAT_BGRA8 ? GS_BGRA : GS_RGBA;
	obs_enter_graphics();
	s->upload_texture = gs_texture_create(cfg->width, cfg->height, fmt, 1, NULL, GS_DYNAMIC);
	obs_leave_graphics();
	return s->upload_texture != NULL;
}

/* (Re)opens the shared surfaces after the producer changed the layer configuration. */
static void apply_config(struct lc_source *s, struct lc_layer *layer, const struct lc_layer_config *cfg)
{
	release_textures(s);
	s->cfg = *cfg;
	s->cfg_valid = true;

	if (cfg->state != LC_STATE_ACTIVE || cfg->width == 0 || cfg->height == 0 || cfg->slot_count == 0)
		return;
	if (!lc_transport_supported(cfg->transport)) {
		blog(LOG_WARNING, "[layercast] '%s': transport %u is not supported by this OBS build", s->active_layer,
		     cfg->transport);
		s->open_failed = true;
		report_open_failure(layer, cfg->generation);
		return;
	}

	bool ok = true;
	if (cfg->transport == LC_TRANSPORT_SHM) {
		ok = open_shm_frames(s, cfg);
	} else if (getenv("LAYERCAST_SIMULATE_GPU_OPEN_FAILURE")) {
		/* Diagnostics: behave as if OBS ran on another GPU, to exercise Minecraft's CPU fallback. */
		ok = false;
	} else {
		uint64_t pid = lc_header_of(&s->map)->producer_pid;
		obs_enter_graphics();
		for (uint32_t i = 0; i < cfg->slot_count && ok; i++) {
			s->textures[i] = lc_open_shared_texture(cfg, i, pid);
			ok = s->textures[i] != NULL;
		}
		obs_leave_graphics();
	}

	if (!ok) {
		blog(LOG_WARNING,
		     "[layercast] '%s': could not open shared surfaces (transport %u, %ux%u); asking Minecraft to fall back",
		     s->active_layer, cfg->transport, cfg->width, cfg->height);
		release_textures(s);
		s->cfg = *cfg;
		s->cfg_valid = true;
		s->open_failed = true;
		report_open_failure(layer, cfg->generation);
		return;
	}

	s->width = cfg->width;
	s->height = cfg->height;
	blog(LOG_INFO, "[layercast] '%s': connected %ux%u, %u slots, transport %u", s->active_layer, cfg->width,
	     cfg->height, cfg->slot_count, cfg->transport);
}

static void upload_shm_frame(struct lc_source *s, uint32_t slot, uint64_t frame)
{
	if (!s->upload_texture || !s->frames.base || frame == s->uploaded_frame)
		return;
	struct lc_shm_frame_header *fh = (struct lc_shm_frame_header *)s->frames.base;
	uint64_t seq = lc_load64(&fh->frame_seq[slot]);
	if (seq & 1u)
		return; /* being written */
	size_t frame_bytes = (size_t)s->cfg.width * s->cfg.height * 4u;
	const uint8_t *data = (const uint8_t *)s->frames.base + LC_SHM_DATA_OFFSET + frame_bytes * slot;
	obs_enter_graphics();
	gs_texture_set_image(s->upload_texture, data, s->cfg.width * 4u, false);
	obs_leave_graphics();
	lc_fence();
	if (lc_load64(&fh->frame_seq[slot]) == seq)
		s->uploaded_frame = frame;
}

static void pull_settings(struct lc_source *s)
{
	if (!s->settings_changed)
		return;
	lc_mutex_lock(&s->settings_mutex);
	bool reconnect = strcmp(s->active_channel, s->channel) != 0 || strcmp(s->active_layer, s->layer_id) != 0;
	strcpy(s->active_channel, s->channel);
	strcpy(s->active_layer, s->layer_id);
	s->active_keep = s->keep_active;
	s->settings_changed = false;
	lc_mutex_unlock(&s->settings_mutex);
	if (reconnect) {
		disconnect(s);
		s->reconnect_timer = RECONNECT_INTERVAL_S;
	}
}

static void lc_source_tick(void *data, float seconds)
{
	struct lc_source *s = data;
	pull_settings(s);

	if (!s->map.base) {
		s->reconnect_timer += seconds;
		if (s->reconnect_timer < RECONNECT_INTERVAL_S)
			return;
		s->reconnect_timer = 0.0f;
		char name[96];
		lc_directory_name(name, sizeof(name), s->active_channel);
		if (!lc_mapping_open(&s->map, name, LC_DIRECTORY_SIZE))
			return;
	}

	uint64_t now = lc_now_ms();
	struct lc_header *h = lc_header_of(&s->map);
	if (!lc_directory_valid(&s->map)) {
		/* Producer shut down (the POSIX name may already point to a new block), is re-initialising, or speaks
		 * another protocol version. Drop the mapping and reopen by name on the next retry. */
		disconnect(s);
		return;
	}
	uint64_t heartbeat = lc_load64(&h->producer_heartbeat_ms);
	if (heartbeat == 0 || now > heartbeat + LC_PRODUCER_TIMEOUT_MS) {
		/* Minecraft closed or froze. Reopen by name later: a restarted game may create a new block. */
		disconnect(s);
		return;
	}
	if (h->session_id != s->session_id) {
		release_textures(s);
		s->session_id = h->session_id;
		s->layer_index = -1;
	}
	if (s->layer_index < 0) {
		s->layer_index = lc_find_layer(&s->map, s->active_layer);
		if (s->layer_index < 0)
			return;
	}

	struct lc_layer *layer = lc_layer_at(&s->map, (uint32_t)s->layer_index);
	/* Lets the game tell that OBS and this plugin are there even while every source is hidden. */
	lc_store64(&layer->consumer_attached_ms, now);
	bool visible = obs_source_showing(s->source) || s->active_keep;
	if (visible) {
		struct obs_video_info ovi;
		uint32_t fps = 0;
		if (obs_get_video_info(&ovi) && ovi.fps_den)
			fps = (ovi.fps_num + ovi.fps_den - 1) / ovi.fps_den;
		lc_store32(&layer->consumer_fps, fps);
		lc_store64(&layer->consumer_heartbeat_ms, now);
	}

	struct lc_layer_config cfg;
	if (!lc_read_layer_config(layer, &cfg))
		return; /* configuration being rewritten */
	if (!s->cfg_valid || cfg.generation != s->cfg.generation)
		apply_config(s, layer, &cfg);
	if (s->open_failed || s->cfg.state != LC_STATE_ACTIVE)
		return;

	uint64_t published = lc_load64(&layer->published);
	if (published == 0)
		return;
	uint32_t slot = lc_published_slot(published);
	if (slot >= s->cfg.slot_count)
		return;
	if (s->cfg.transport == LC_TRANSPORT_SHM) {
		upload_shm_frame(s, slot, lc_published_frame(published));
		s->has_frame = s->uploaded_frame != 0;
	} else {
		s->has_frame = s->textures[slot] != NULL;
	}
	s->draw_slot = slot;
	s->last_frame = lc_published_frame(published);
	lc_store32(&layer->consumer_reading_slot, slot);
}

static void lc_source_render(void *data, gs_effect_t *unused)
{
	(void)unused;
	struct lc_source *s = data;
	if (!s->has_frame)
		return;
	gs_texture_t *tex = s->cfg.transport == LC_TRANSPORT_SHM ? s->upload_texture : s->textures[s->draw_slot];
	if (!tex)
		return;

	bool rect = gs_texture_is_rect(tex);
	gs_effect_t *effect = rect ? lc_effect_rect : lc_effect_2d;
	if (!effect)
		return;

	const char *technique = "Draw";
	if (s->cfg.flags & LC_FLAG_OPAQUE)
		technique = "DrawOpaque";
	else if (s->cfg.flags & LC_FLAG_PREMULTIPLIED)
		technique = "DrawUnpremultiply";

	/* Frames are 8-bit sRGB-encoded values; pass them through untouched like other SDR sources. */
	const bool previous_srgb = gs_framebuffer_srgb_enabled();
	gs_enable_framebuffer_srgb(false);

	gs_effect_set_texture(gs_effect_get_param_by_name(effect, "image"), tex);
	uint32_t flip = (s->cfg.flags & LC_FLAG_FLIP_Y) ? GS_FLIP_V : 0;
	while (gs_effect_loop(effect, technique))
		gs_draw_sprite(tex, flip, 0, 0);

	gs_enable_framebuffer_srgb(previous_srgb);
}

static uint32_t lc_source_width(void *data)
{
	return ((struct lc_source *)data)->width;
}

static uint32_t lc_source_height(void *data)
{
	return ((struct lc_source *)data)->height;
}

static void lc_source_update(void *data, obs_data_t *settings)
{
	struct lc_source *s = data;
	lc_mutex_lock(&s->settings_mutex);
	lc_sanitize_channel(s->channel, sizeof(s->channel), obs_data_get_string(settings, S_CHANNEL));
	const char *layer = obs_data_get_string(settings, S_LAYER);
	snprintf(s->layer_id, sizeof(s->layer_id), "%s", (layer && *layer) ? layer : "game");
	s->keep_active = obs_data_get_bool(settings, S_KEEP_ACTIVE);
	s->settings_changed = true;
	lc_mutex_unlock(&s->settings_mutex);
}

static void *lc_source_create(obs_data_t *settings, obs_source_t *source)
{
	struct lc_source *s = bzalloc(sizeof(struct lc_source));
	s->source = source;
	s->layer_index = -1;
	s->reconnect_timer = RECONNECT_INTERVAL_S;
	if (!lc_mutex_init(&s->settings_mutex)) {
		bfree(s);
		return NULL;
	}
	lc_source_update(s, settings);
	return s;
}

static void lc_source_destroy(void *data)
{
	struct lc_source *s = data;
	disconnect(s);
	lc_mutex_destroy(&s->settings_mutex);
	bfree(s);
}

static void lc_source_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, S_CHANNEL, "default");
	obs_data_set_default_string(settings, S_LAYER, "game");
	obs_data_set_default_bool(settings, S_KEEP_ACTIVE, false);
}

static const char *builtin_label(const char *id)
{
	for (size_t b = 0; b < sizeof(builtin_layers) / sizeof(builtin_layers[0]); b++) {
		if (strcmp(builtin_layers[b].id, id) == 0)
			return obs_module_text(builtin_layers[b].text_key);
	}
	return NULL;
}

/*
 * Fills the layer list with the layers Minecraft currently offers: the game picture minus everything split out, and
 * whatever the player split out in the mod's config. Layers that stay merged are listed by the producer with LC_STATE_EMPTY and
 * are left out. Returns the number of offered layers, or -1 if Minecraft is not running on this channel.
 */
static int add_layer_items(obs_property_t *list, const char *channel_setting, const char *current)
{
	char channel[LC_CHANNEL_MAX_LEN + 1];
	lc_sanitize_channel(channel, sizeof(channel), channel_setting);
	char name[96];
	lc_directory_name(name, sizeof(name), channel);

	int offered = -1;
	bool current_listed = false;
	char current_name[LC_NAME_LEN + 1] = "";
	struct lc_mapping map;
	if (lc_mapping_open(&map, name, LC_DIRECTORY_SIZE)) {
		if (lc_directory_valid(&map) && lc_producer_alive(&map)) {
			offered = 0;
			const struct lc_header *h = lc_header_of(&map);
			for (uint32_t i = 0; i < h->layer_count && i < LC_MAX_LAYERS; i++) {
				const struct lc_layer *layer = lc_layer_at(&map, i);
				char id[LC_ID_LEN + 1], display[LC_NAME_LEN + 1], label[LC_NAME_LEN + LC_ID_LEN + 8];
				memcpy(id, layer->id, LC_ID_LEN);
				id[LC_ID_LEN] = '\0';
				memcpy(display, layer->name, LC_NAME_LEN);
				display[LC_NAME_LEN] = '\0';
				if (!id[0])
					continue;
				if (lc_load32(&layer->state) == LC_STATE_EMPTY) {
					if (strcmp(id, current) == 0)
						strcpy(current_name, display);
					continue;
				}
				/* Prefer the localized name of built-in layers over the producer's English one. */
				const char *label_text = builtin_label(id);
				snprintf(label, sizeof(label), "%s (%s)", label_text ? label_text : display, id);
				obs_property_list_add_string(list, label, id);
				offered++;
				current_listed = current_listed || strcmp(id, current) == 0;
			}
		}
		lc_mapping_close(&map);
	}
	if (offered < 0) {
		/* Minecraft is not running: only the layer that always exists, plus the source's own choice. */
		static const char *always[] = {"game"};
		for (size_t i = 0; i < sizeof(always) / sizeof(always[0]); i++) {
			char label[128];
			snprintf(label, sizeof(label), "%s (%s)", builtin_label(always[i]), always[i]);
			obs_property_list_add_string(list, label, always[i]);
			current_listed = current_listed || strcmp(always[i], current) == 0;
		}
	}
	if (!current_listed && current && *current) {
		/* Keep the source's setting visible (and selected) even though Minecraft does not offer it right now. */
		char label[160];
		const char *label_text = builtin_label(current);
		if (!label_text)
			label_text = current_name[0] ? current_name : current;
		snprintf(label, sizeof(label), "%s (%s) — %s", label_text, current,
			 obs_module_text(offered < 0 ? "Layer.Unknown" : "Layer.NotOffered"));
		obs_property_list_add_string(list, label, current);
	}
	return offered;
}

static void update_status(obs_properties_t *props, int offered, const char *channel)
{
	obs_property_t *status = obs_properties_get(props, S_STATUS);
	if (!status)
		return;
	char text[512];
	if (offered < 0)
		snprintf(text, sizeof(text), obs_module_text("Status.NotRunning"), channel);
	else
		snprintf(text, sizeof(text), obs_module_text("Status.Offered"), offered);
	obs_property_set_description(status, text);
}

/*
 * Lists the Minecraft games sharing layers right now. A game whose configured channel is taken by another running game
 * shares on <channel>-2 .. -9 instead, so those are probed for the current channel and for "default".
 */
static void add_channel_items(obs_property_t *list, const char *current_setting)
{
	char current[LC_CHANNEL_MAX_LEN + 1];
	lc_sanitize_channel(current, sizeof(current), current_setting);
	char bases[2][LC_CHANNEL_MAX_LEN + 1];
	snprintf(bases[0], sizeof(bases[0]), "%s", current);
	size_t len = strlen(bases[0]);
	if (len > 2 && bases[0][len - 2] == '-' && bases[0][len - 1] >= '2' && bases[0][len - 1] <= '9')
		bases[0][len - 2] = '\0';
	snprintf(bases[1], sizeof(bases[1]), "default");
	int base_count = strcmp(bases[0], bases[1]) == 0 ? 1 : 2;

	bool current_listed = false;
	for (int b = 0; b < base_count; b++) {
		for (int n = 1; n <= 9; n++) {
			char candidate[LC_CHANNEL_MAX_LEN + 1];
			if (n == 1)
				snprintf(candidate, sizeof(candidate), "%.*s", (int)LC_CHANNEL_MAX_LEN, bases[b]);
			else
				snprintf(candidate, sizeof(candidate), "%.*s-%d", (int)LC_CHANNEL_MAX_LEN - 2, bases[b], n);
			char name[96];
			lc_directory_name(name, sizeof(name), candidate);
			struct lc_mapping map;
			if (!lc_mapping_open(&map, name, LC_DIRECTORY_SIZE))
				continue;
			if (lc_directory_valid(&map) && lc_producer_alive(&map)) {
				const struct lc_header *h = lc_header_of(&map);
				char producer[sizeof(h->producer_name) + 1];
				memcpy(producer, h->producer_name, sizeof(h->producer_name));
				producer[sizeof(h->producer_name)] = '\0';
				char label[192];
				snprintf(label, sizeof(label), "%s  —  %s, PID %llu", candidate, producer,
					 (unsigned long long)h->producer_pid);
				obs_property_list_add_string(list, label, candidate);
				current_listed = current_listed || strcmp(candidate, current) == 0;
			}
			lc_mapping_close(&map);
		}
	}
	if (!current_listed)
		obs_property_list_add_string(list, current, current);
}

static bool channel_modified(obs_properties_t *props, obs_property_t *property, obs_data_t *settings)
{
	(void)property;
	obs_property_t *list = obs_properties_get(props, S_LAYER);
	obs_property_list_clear(list);
	const char *channel = obs_data_get_string(settings, S_CHANNEL);
	int offered = add_layer_items(list, channel, obs_data_get_string(settings, S_LAYER));
	update_status(props, offered, channel);
	return true;
}

static bool refresh_clicked(obs_properties_t *props, obs_property_t *property, void *data)
{
	struct lc_source *s = data;
	if (!s)
		return false;
	obs_data_t *settings = obs_source_get_settings(s->source);
	obs_property_t *channels = obs_properties_get(props, S_CHANNEL);
	obs_property_list_clear(channels);
	add_channel_items(channels, obs_data_get_string(settings, S_CHANNEL));
	channel_modified(props, property, settings);
	obs_data_release(settings);
	return true;
}

static obs_properties_t *lc_source_properties(void *data)
{
	struct lc_source *s = data;
	obs_properties_t *props = obs_properties_create();

	/* Editable: pick a running game, or type any channel name. */
	obs_property_t *channel = obs_properties_add_list(props, S_CHANNEL, obs_module_text("Channel"), OBS_COMBO_TYPE_EDITABLE,
							  OBS_COMBO_FORMAT_STRING);
	obs_property_set_long_description(channel, obs_module_text("Channel.Description"));
	obs_property_set_modified_callback(channel, channel_modified);
	if (s) {
		obs_data_t *settings = obs_source_get_settings(s->source);
		add_channel_items(channel, obs_data_get_string(settings, S_CHANNEL));
		obs_data_release(settings);
	} else {
		add_channel_items(channel, "default");
	}

	obs_property_t *list = obs_properties_add_list(props, S_LAYER, obs_module_text("Layer"), OBS_COMBO_TYPE_LIST,
						       OBS_COMBO_FORMAT_STRING);
	obs_properties_add_text(props, S_STATUS, "", OBS_TEXT_INFO);
	if (s) {
		obs_data_t *settings = obs_source_get_settings(s->source);
		const char *channel_value = obs_data_get_string(settings, S_CHANNEL);
		update_status(props, add_layer_items(list, channel_value, obs_data_get_string(settings, S_LAYER)), channel_value);
		obs_data_release(settings);
	} else {
		update_status(props, add_layer_items(list, "default", "game"), "default");
	}
	obs_properties_add_button2(props, "refresh", obs_module_text("Refresh"), refresh_clicked, s);

	obs_property_t *keep = obs_properties_add_bool(props, S_KEEP_ACTIVE, obs_module_text("KeepActive"));
	obs_property_set_long_description(keep, obs_module_text("KeepActive.Description"));
	return props;
}

struct obs_source_info layercast_source_info = {
	.id = "layercast_layer_source",
	.type = OBS_SOURCE_TYPE_INPUT,
	.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW,
	.get_name = lc_source_get_name,
	.create = lc_source_create,
	.destroy = lc_source_destroy,
	.update = lc_source_update,
	.get_defaults = lc_source_defaults,
	.get_properties = lc_source_properties,
	.video_tick = lc_source_tick,
	.video_render = lc_source_render,
	.get_width = lc_source_width,
	.get_height = lc_source_height,
	.icon_type = OBS_ICON_TYPE_GAME_CAPTURE,
};
