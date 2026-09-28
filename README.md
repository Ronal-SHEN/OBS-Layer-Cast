# OBS Layer Cast

**English** | [简体中文](README.zh-CN.md)

OBS Layer Cast hooks into the Minecraft client's rendering pipeline to split F3, the scoreboard, boss bars, the TAB list, name tags, other HUD elements and HUDs drawn by other mods into independent transparent layers, then hands them to OBS through GPU texture sharing. In OBS each layer is a separate source, so streamers can freely control its visibility, position, scale and filters, while the player's own game view stays unchanged.

The project has two parts: a Minecraft mod in [`mod/`](mod) (Fabric / NeoForge) and an OBS plugin in [`obs-plugin/`](obs-plugin).

## Layers

Layers are **subtractive**. By default there is a single layer, `game`: the complete picture the player sees (world + all HUD and screens). In the mod's **Split layers** settings you choose which parts to split out. Each split part becomes its own transparent layer **and is removed from `game`**. Stacked in OBS, `game` plus the split layers add up exactly to the game image, with nothing drawn twice.

| id | Content |
|---|---|
| `game` | Game image minus the split parts |
| `hotbar` | Hotbar and status bars |
| `crosshair` | Crosshair |
| `effects` | Status effects |
| `bossbar` | Boss bars |
| `scoreboard` | Scoreboard |
| `actionbar` | Action bar messages |
| `title` | Titles and subtitles |
| `chat` | Chat |
| `tablist` | Player list (TAB) |
| `subtitles` | Sound subtitles |
| `debug` | Debug screen (F3) |
| `screen` | Open screens / menus |
| `toasts` | Toasts |
| `camera` | Camera overlays |
| `nametags` | Name tags |
| `mod.<mod id>` | One mod's HUD |

All layers have the window's size and a transparent background, so they line up pixel-perfectly when stacked in OBS, and each can be cropped, scaled, moved and filtered on its own.

**Other mods' HUDs** are detected automatically the first time they draw, then appear in **Split layers** (they stay in `game` until you split them). Tested: MiniHUD, Xaero's Minimap, JourneyMap, Jade, Inventory HUD+, Armor HUD, Immersive Overlays, AppleSkin, Status Effect Timer. A mod that only adds to a vanilla part (for example a timer on the status effect icons) follows that part.

## Usage

### Install

**Mod** — put `layercast+<loader>+<version>+<mc version>.jar` into `mods/` together with its dependencies (Java 25 is required, as for Minecraft 26.x itself):

- **Fabric**: Fabric Loader ≥ 0.19, Fabric API, [MaLiLib](https://modrinth.com/mod/malilib) (Mod Menu optional)
- **NeoForge**: [MaFgLib](https://modrinth.com/mod/mafglib) and its dependency FoxifiedClassTweaker

| Minecraft | Fabric | NeoForge |
|---|---|---|
| 26.1 – 26.1.2 | ✅ | ✅ |
| 26.2 | ✅ | ✅ |
| 26.3 | ✅ | ⏳ waiting for MaFgLib |

**OBS plugin** (OBS 32.x) — download it from [Releases](https://github.com/Ronal-SHEN/OBS-Layer-Cast/releases):

| OS | Location |
|---|---|
| Windows | `obs-layercast.dll` → `C:\ProgramData\obs-studio\plugins\obs-layercast\bin\64bit\`, the `data/` folder → `C:\ProgramData\obs-studio\plugins\obs-layercast\data\` |
| macOS | `obs-layercast.plugin` → `~/Library/Application Support/obs-studio/plugins/` |
| Linux | `obs-layercast.so` → `~/.config/obs-studio/plugins/obs-layercast/bin/64bit/`, `data/` → the `data/` folder next to it |

### Stream with layers

1. Start Minecraft and OBS, in any order. Either side reconnects automatically after a restart.
2. In game, press `J` + `C` to open the settings and turn on the parts you want to split in **Split layers**.
3. In OBS, add a **Minecraft Layer (LayerCast)** source. A new source shows `game` by default. Add one more source per split layer, place them **above** `game`, then move, scale or filter each one.
4. After splitting or merging in game, click **Refresh layer list** in the source properties. A merged layer goes straight back into `game`; its source turns blank but keeps its settings, and comes back when you split it again.

For example, with only `chat` and `mod.xaerominimap` split, `game` shows everything except chat and the minimap, and those two sources can be placed, enlarged or hidden freely.

A layer is only rendered while an OBS source is showing it, so unused layers cost nothing. Enable the source option **Keep rendering while hidden** to avoid a blank frame when a hidden source reappears.

### Hotkeys and settings

| Default hotkey | Action |
|---|---|
| `J` + `C` | Open settings (also available from Mod Menu / the NeoForge mod list) |
| `J` + `S` | Show sharing status in game |
| Unbound | Master switch, one toggle per split layer (split or merge live while streaming), export layers as PNG |

Settings are stored in `config/layercast.json`. Besides **Split layers**, you can set the channel, the layer frame rate cap (default 60, never above OBS's frame rate) and the transport (auto / GPU only / CPU).

**Several Minecraft instances**: if the configured channel is already used by another running game, the next one automatically switches to `<channel>-2`, `-3`, … and tells you in game. The source's **Channel** dropdown in OBS lists every game that is sharing. To keep an OBS source tied to one game across restarts, give each instance its own channel.
