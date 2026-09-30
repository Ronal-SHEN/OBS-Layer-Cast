# OBS Layer Cast

[English](README.md) | **简体中文**

基于 Minecraft 客户端渲染流程，将 F3、Scoreboard、Bossbar、TAB、名字标签等及自定义 HUD 拆分为独立的透明渲染层，并通过 GPU 纹理共享传递给 OBS。OBS 侧将各层作为独立 Source 接入，使主播能够自由控制其显示、位置、缩放及滤镜效果，同时不影响玩家本地游戏画面。

项目由两部分组成：[`mod/`](mod) 中的 Minecraft 模组（Fabric / NeoForge）和 [`obs-plugin/`](obs-plugin) 中的 OBS 插件。

## 图层

采用"减法"：默认只有一个图层 `game`，即玩家看到的完整画面（世界 + 全部 HUD/界面）。在模组配置的「拆分图层」页里选择要单独拆出来的部分，被拆出的部分成为独立的透明图层，**同时从 `game` 中去掉**。在 OBS 里把 `game` 和各拆分图层叠起来，正好等于游戏画面，不会重复。

| id | 内容 |
|---|---|
| `game` | 游戏画面（去掉已拆分的部分） |
| `hotbar` | 快捷栏与状态条 |
| `crosshair` | 准星 |
| `effects` | 状态效果 |
| `bossbar` | Boss 血条 |
| `scoreboard` | 计分板 |
| `actionbar` | 动作栏消息 |
| `title` | 标题与副标题 |
| `chat` | 聊天栏 |
| `tablist` | 玩家列表 (Tab) |
| `subtitles` | 声音字幕 |
| `debug` | 调试屏幕 (F3) |
| `screen` | 打开的界面/菜单 |
| `toasts` | 弹出提示 |
| `camera` | 镜头覆盖层 |
| `nametags` | 名字标签 |
| `mod.<模组 id>` | 某个模组的 HUD |

所有图层都与窗口同尺寸、透明背景，在 OBS 中叠放即与游戏画面逐像素对齐，可以单独裁剪、缩放、移动、加滤镜。

**其他模组的 HUD** 会在它第一次绘制时被自动识别，随后出现在「拆分图层」页里（拆分前仍留在 `game` 中）。已测试：MiniHUD、Xaero's Minimap、JourneyMap、Jade、Inventory HUD+、Armor HUD、Immersive Overlays、AppleSkin、Status Effect Timer。只在原版部分上追加内容的模组（例如在状态效果图标上叠加计时）跟随那个原版部分。

## 使用

### 安装

**模组**：把 `layercast+<加载器>+<版本>+<MC 版本>.jar` 和依赖一起放进 `mods/`（需要 Java 25，与 Minecraft 26.x 本身的要求相同）：

- **Fabric**：Fabric Loader ≥ 0.19、Fabric API、[MaLiLib](https://modrinth.com/mod/malilib)（Mod Menu 可选）
- **NeoForge**：[MaFgLib](https://modrinth.com/mod/mafglib) 及其依赖 FoxifiedClassTweaker

| Minecraft | Fabric | NeoForge |
|---|---|---|
| 26.1 – 26.1.2 | ✅ | ✅ |
| 26.2 | ✅ | ✅ |
| 26.3 | ✅ | ⏳ 等待 MaFgLib |

**OBS 插件**（OBS 32.x）：从 [Releases](https://github.com/Ronal-SHEN/OBS-Layer-Cast/releases) 下载。

- **Windows**：把 `obs-layercast-windows.zip` 解压到 `C:\ProgramData\obs-studio\plugins\`。目录应该是：

  ```
  C:\ProgramData\obs-studio\plugins\obs-layercast\
  ├── bin\
  │   └── 64bit\
  │       └── obs-layercast.dll
  └── data\
  ```

- **macOS**（13+，Apple 芯片）：把 `obs-layercast-macos.zip` 解压到 `~/Library/Application Support/obs-studio/plugins/`。目录应该是：

  ```
  ~/Library/Application Support/obs-studio/plugins/obs-layercast.plugin/
  └── Contents/
      ├── MacOS/
      │   └── obs-layercast
      └── Resources/
  ```

- **Linux**：把 `obs-layercast-linux-x86_64.zip` 或 `obs-layercast-linux-arm64.zip` 解压到 `~/.config/obs-studio/plugins/`。目录应该是：

  ```
  ~/.config/obs-studio/plugins/obs-layercast/
  ├── bin/
  │   └── 64bit/
  │       └── obs-layercast.so
  └── data/
  ```

插件需要它的 `data/` 文件夹（绘制图层用的着色器和界面文字），缺少它 OBS 不会加载插件。Windows 和 Linux 上 `data/` 与插件程序放在同一个 `obs-layercast/` 文件夹里，所以两者一起打成 zip 发布；macOS 上它在 `.plugin` 包内部（`Contents/Resources/`），而 `.plugin` 本身就是一个文件夹，所以同样打成 zip。

### 直播中使用图层

1. 启动 Minecraft 和 OBS，顺序无关，任意一方重启后都会自动重连。
2. 在游戏中按 `J` + `C` 打开配置，在「拆分图层」页打开想要单独处理的部分。
3. 在 OBS 中添加来源「Minecraft 图层 (LayerCast)」，新来源默认就是 `game`；再为每个拆分出来的图层各添加一个来源，放在 `game` **之上**，各自移动、缩放、加滤镜。
4. 在游戏中拆分或合并后，在来源属性里点击「刷新图层列表」。合并回去的图层会立刻回到 `game` 里，对应的来源变成空白但保留设置，重新拆分后自动恢复。

例如只拆出 `chat` 和 `mod.xaerominimap`：`game` 显示除聊天和小地图以外的全部画面，聊天和小地图两个来源可以任意摆放、放大或隐藏。

只有 OBS 中有来源正在显示某个图层时，模组才会渲染它，不用的图层没有开销。勾选来源选项「隐藏时保持渲染」可以避免来源重新显示时出现一帧空白。

### 热键与配置

| 默认热键 | 功能 |
|---|---|
| `J` + `C` | 打开配置界面（也可从 Mod Menu / NeoForge 模组列表进入） |
| `J` + `S` | 在游戏内显示共享状态 |
| 未绑定 | 总开关、每个图层的拆分开关（直播中可随时拆分/合并）、导出图层为 PNG |

配置文件为 `config/layercast.json`。除「拆分图层」外，还可以设置频道、图层最大帧率（默认 60，且不超过 OBS 帧率）和传输方式（自动 / 仅 GPU / CPU）。

**同时运行多个 Minecraft**：如果配置的频道已被另一个正在运行的游戏占用，后启动的游戏会自动改用 `<频道>-2`、`-3`……，并在游戏内提示。OBS 来源的「频道」下拉框会列出所有正在共享的游戏。想让 OBS 来源在重启后仍固定对应某个游戏，就给每个实例设置不同的频道。
