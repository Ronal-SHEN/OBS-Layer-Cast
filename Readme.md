# OBS Layer Cast

基于 Minecraft 客户端渲染流程，将 F3、Scoreboard、Bossbar、TAB、名字标签等及自定义 HUD 拆分为独立的透明渲染层，并通过 GPU 纹理共享传递给 OBS。OBS 侧将各层作为独立 Source 接入，使主播能够自由控制其显示、位置、缩放及滤镜效果，同时不影响玩家本地游戏画面。

项目由两部分组成：

| 组件 | 目录 | 说明 |
|---|---|---|
| Minecraft 模组 | [`mod/`](mod) | Fabric / NeoForge，多 Minecraft 版本（Stonecutter），配置界面与热键基于 MaLiLib |
| OBS 插件 | [`obs-plugin/`](obs-plugin) | C 语言插件，提供来源类型「Minecraft 图层 (LayerCast)」 |

设计与原理（GPU 画面传递、同步、协议）见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 图层

采用"减法"：默认只有一个图层 `game`，即玩家看到的完整画面（世界 + 全部 HUD/界面）。玩家在模组配置的「拆分图层」页里选择要单独拆出来的部分，被拆出的部分成为独立的透明图层，**同时从 `game` 中去掉**。所以 OBS 里的结果是「完整画面减去已拆分的部分」加上「拆分出来的各个图层」，叠起来正好等于游戏画面，不会重复。

| id | 内容 | 说明 |
|---|---|---|
| `game` | 游戏画面（去掉已拆分的部分） | 始终提供，不透明，放在最底层。什么都不拆分时就是游戏最终画面的直接拷贝；拆分全部部分时只剩世界画面 |
| `hotbar` | 快捷栏与状态条 | 可拆分。快捷栏、生命、护甲、饥饿、氧气、经验、手持物品名 |
| `crosshair` | 准星 | 可拆分。原版准星是反色绘制；拆出后在 OBS 中给该来源设置混合方式「差值 (Difference)」即可还原反色效果 |
| `effects` | 状态效果 | 可拆分 |
| `bossbar` | Boss 血条 | 可拆分 |
| `scoreboard` | 计分板 | 可拆分 |
| `actionbar` | 动作栏消息 | 可拆分 |
| `title` | 标题与副标题 | 可拆分 |
| `chat` | 聊天栏 | 可拆分。包括打开聊天界面时的消息、输入框和命令补全 |
| `tablist` | 玩家列表 (Tab) | 可拆分 |
| `subtitles` | 声音字幕 | 可拆分 |
| `debug` | 调试屏幕 (F3) | 可拆分。含 F3 图表 |
| `screen` | 打开的界面/菜单 | 可拆分。物品栏、菜单等 |
| `toasts` | 弹出提示 | 可拆分 |
| `camera` | 镜头覆盖层 | 可拆分。暗角（vignette）、南瓜模糊、传送门、睡眠等 |
| `nametags` | 名字标签 | 可拆分（26.1.x、26.2）。玩家和有名字的生物头顶的名字。它画在世界里而不是 HUD 中：被方块遮挡的方式、穿墙可见的半透明部分都与游戏里一样，被第一人称手臂挡住的部分也不在图层里。拆出后 `game` 中对应位置换成名字背后的画面，隐藏这个来源即可在直播中去掉所有名字（例如防止被找到位置） |
| `mod.<模组 id>` | 某个模组的 HUD | 可拆分。每个画了 HUD 的模组各有一个，例如 `mod.minihud`、`mod.xaerominimap`、`mod.jade` |

所有拆分图层都与窗口同尺寸、透明背景，在 OBS 中叠放即与游戏画面逐像素对齐；可以单独裁剪、缩放、移动、加滤镜。

**按需渲染**：只有当 OBS 中有来源正在显示（预览或节目中可见）某个图层时，模组才会渲染它；不用的图层零开销。

**OBS 中只列出当前提供的图层**：来源属性的「图层」下拉框只显示 `game` 和已经拆分出来的图层。在游戏中拆分或合并后，点击「刷新图层列表」即可更新；合并回去的图层会立刻重新出现在 `game` 里，对应的来源变成空白，并在下拉框里标注「未拆分」，保留原来的设置，重新拆分后自动恢复。Minecraft 未运行时只列出 `game`。

### 其他模组的 HUD

模组会在它第一次画 HUD 时被自动识别（约每秒抽查一帧，不需要连着 OBS），随后出现在「拆分图层」页里，默认仍留在 `game` 中；识别结果写进配置文件，下次启动直接可用。识别方法：看是哪个模组的代码画了这个元素（按类所属的模组 jar 判断；像 MaLiLib 这样被其他模组依赖的库，会把元素记给调用它的模组）；NeoForge 上模组注册的 GUI 层直接按层 ID 的命名空间识别。如果模组只是在某个原版部分上追加内容（例如在状态效果图标上叠加计时），它跟随那个原版部分。

已实测（Minecraft 26.2，图层导出与游戏截图逐一对比，并在 macOS 与 Windows 的 OBS 中确认）：

| 模组 | Fabric | NeoForge | 拆分后所在图层 |
|---|---|---|---|
| MiniHUD（信息行） | ✅ | —（无 NeoForge 版） | `mod.minihud` |
| Xaero's Minimap | ✅ | ✅ | `mod.xaerominimap` |
| JourneyMap（小地图） | ✅ | ✅ | `mod.journeymap` |
| Jade（方块信息） | ✅ | ✅ | `mod.jade` |
| Inventory HUD+ | ✅ | ✅ | `mod.inventoryhud` |
| Armor HUD | ✅ | ✅ | `mod.armor_hud` |
| Immersive Overlays | ✅ | ✅ | `mod.immersiveoverlays` |
| AppleSkin（饱食度预览） | ✅ | ✅ | Fabric：随 `hotbar`（它挂在原版饥饿条上）；NeoForge：`mod.appleskin`（它注册为独立的 GUI 层） |
| Status Effect Timer | ✅ | — | 随 `effects` |

26.1.2 与 26.3（Fabric）另外测了 MiniHUD 与 Xaero's Minimap。BetterF3 19.0.0 在 26.2 上打开 F3 时自身崩溃（与本模组无关），未能测试。

识别的开销：只在有模组图层被拆分、并且本帧要渲染 `game` 或某个模组图层时才进行，每个元素约 2–5 µs（macOS M5 约 2.3 µs，Windows 约 4.7 µs；同时开着 5 个 HUD 模组时每秒约 1800 个元素）。

## 支持情况

| Minecraft | Fabric | NeoForge |
|---|---|---|
| 26.1 – 26.1.2 | ✅ | ✅ |
| 26.2 | ✅ | ✅ |
| 26.3 | ✅ | ⏳ 等待 MaFgLib 发布 26.3 版本（NeoForge 26.3 目前也仍是 beta） |

| 平台 | OpenGL 后端（默认） | Vulkan 后端（实验性，26.2+） |
|---|---|---|
| Windows | D3D11 共享纹理 + `WGL_NV_DX_interop2`（零拷贝） | D3D11 NT 句柄导入 Vulkan（零拷贝） |
| macOS | 全局 IOSurface + CGL（零拷贝） | MoltenVK `VK_EXT_metal_objects` + IOSurface（零拷贝） |
| Linux | CPU 共享内存 | CPU 共享内存 |
| 任意平台兜底 | 当 GPU 共享不可用（例如 OBS 与游戏运行在不同显卡上）时自动切换为 CPU 共享内存 | 同左 |

已端到端验证（游戏 → OBS 截图对比）：macOS（Apple M5）OpenGL/Vulkan、Windows 11（RTX 5060 Ti）OpenGL/Vulkan、CPU 兜底路径、GPU→CPU 自动降级、窗口尺寸变化、Minecraft/OBS 各自重启后的重新连接；26.1.2 / 26.2 / 26.3 × Fabric、26.1.2 / 26.2 × NeoForge 的图层捕获。Linux：Ubuntu 24.04（X11、RTX 5060 Ti、NVIDIA 595）上同样的测试全部通过（OpenGL 与 Vulkan 后端，经 CPU 共享内存路径到 OBS 32.2），包括多个游戏同时运行。Windows 11 双显卡笔记本（RTX 3050 Laptop + AMD 核显）上同样全部通过，并实测了 OBS 在另一块显卡上时自动降级为 CPU 路径。

## 安装

### 模组

把 `layercast-<loader>-<版本>+<mc版本>.jar` 放进 `mods/`，并安装依赖：

- **Fabric**：Fabric Loader ≥ 0.19、Fabric API、[MaLiLib](https://modrinth.com/mod/malilib)（可选 Mod Menu）
- **NeoForge**：[MaFgLib](https://modrinth.com/mod/mafglib)（MaLiLib 的 NeoForge 移植）及其依赖 FoxifiedClassTweaker

需要 Java 25（Minecraft 26.x 本身的要求）。模组通过 Java FFM 调用系统图形接口，不附带任何原生库；如果启动器没有传入 `--enable-native-access=ALL-UNNAMED`，Java 会打印一行提示，不影响使用。

### OBS 插件（OBS 32.x）

| 系统 | 安装位置 |
|---|---|
| Windows | 把 `obs-layercast.dll` 放到 `C:\ProgramData\obs-studio\plugins\obs-layercast\bin\64bit\`，`data/` 目录内容放到 `C:\ProgramData\obs-studio\plugins\obs-layercast\data\` |
| macOS | 把 `obs-layercast.plugin` 放到 `~/Library/Application Support/obs-studio/plugins/` |
| Linux | `obs-layercast.so` 放到 `~/.config/obs-studio/plugins/obs-layercast/bin/64bit/`，`data/` 放到同目录的 `data/` |

## 使用

1. 启动 Minecraft 和 OBS（顺序无关，任意一方重启后都会自动重连）。
2. 在游戏中按 `L` + `C` 打开配置，在「拆分图层」页打开想要单独处理的部分（原版 HUD 部分，以及识别到的模组 HUD）。
3. OBS 中添加来源「Minecraft 图层 (LayerCast)」，新来源默认就是 `game`；再为每个拆分出来的图层各添加一个来源，放在 `game` **之上**，各自移动、缩放、加滤镜。
4. 例如只拆出 `chat` 和 `mod.xaerominimap`：`game` 显示除聊天和小地图以外的全部画面，聊天和小地图两个来源可以任意摆放、放大或隐藏。
5. 同时运行多个 Minecraft 时互不干扰：如果配置的频道已被另一个正在运行的 Minecraft 占用，后启动的游戏自动改用 `<频道>-2`、`-3`……（最多 `-9`），并在进入世界时提示实际使用的频道。OBS 来源的「频道」下拉框会列出所有正在共享的游戏（频道名 + 加载器 + PID）。想让 OBS 来源在重启后仍固定对应某个游戏，就在每个实例的配置里设置不同的频道。

来源选项「隐藏时保持渲染」：默认情况下来源不可见时游戏会停止渲染该图层以节省性能，勾选后可避免重新显示时出现一帧空白。

### 配置与热键（MaLiLib）

| 默认热键 | 功能 |
|---|---|
| `L` + `C` | 打开配置界面（也可从 Mod Menu / NeoForge 模组列表进入） |
| `L` + `S` | 在游戏内显示共享状态 |
| 未绑定 | 总开关、每个拆分开关（直播中可随时拆分/合并）、导出图层为 PNG（诊断用） |

配置项：总开关、频道、图层最大帧率（默认 60，且不超过 OBS 帧率）、传输方式（自动 / 仅 GPU / CPU）、每层缓冲数、无 OBS 时也渲染；「拆分图层」页：每个原版部分与每个识别到的模组 HUD 是否拆分为独立图层（默认全部合并）。配置文件为 `config/layercast.json`（`Split` 与 `SplitMods` 两节）。

## 性能

Windows 11 / RTX 5060 Ti / OpenGL / 1385×831，Sodium + Xaero 小地图 + MiniHUD + Tweakeroo + Litematica，OBS 以 30 FPS 读取 `game`、`chat`、`hotbar`、`debug`、`mod.xaerominimap` 五个来源（`LayerCastBenchmark` 游戏测试）：

| 场景 | 不限帧 FPS | 不限帧 p99 | 限 120 FPS 时的 FPS | 限 120 FPS 时的 p99 |
|---|---|---|---|---|
| LayerCast 关闭 | 685 | 2.06 ms | 119.4 | 9.26–9.63 ms |
| 只共享 `game`（不拆分） | 670 | 2.20 ms | 119.3 | 9.24 ms |
| `game` + 4 个拆分图层（上面五个来源） | 666 | 2.23 ms | 119.3 | 9.23 ms |
| 全部 17 个图层拆分 | 655 | 2.22 ms | 119.3 | 9.32 ms |

在常见的限帧设置下，帧率和帧时间分布与关闭时一样，也没有额外的卡顿帧。每次向 OBS 送出图层时（≤ OBS 帧率），渲染线程多花约 0.56 ms CPU、0.13 ms GPU（上表第三行，限 120 FPS）：GUI 分流与模组归属 0.10 ms、世界拷贝 0.04 ms、`game` 重绘 0.07 ms、4 个拆分图层 0.15 ms、Windows 共享纹理加锁 + 解锁 0.17 ms、拷贝 0.03 ms。

做法：

- 需要更新的图层都在**同一帧**里捕获，而不是分摊到多帧：分流和共享纹理同步这些固定开销每帧只付一次，OBS 拿到的各图层也来自同一帧画面。
- Windows + OpenGL：D3D11 共享纹理的 interop 加锁/解锁每次都会阻塞约 0.07 ms，现在每帧对所有要写入的纹理只加锁、解锁一次（以前是每个图层各一次），拷贝用 `glCopyImageSubData`，不再切换帧缓冲。
- 暂时没有内容的拆分图层（标题、动作栏、Tab 列表等大部分时间都是空的）在 OBS 已经显示空白帧后不再重绘和拷贝，直到重新出现内容。

诊断：在游戏的 JVM 参数里加 `-Dlayercast.profile=true`（Prism：编辑实例 → 设置 → Java → JVM 参数），日志中每 10 秒输出一行 `[profile]`：共享帧与普通帧的平均帧间隔、各阶段的 CPU/GPU 耗时、模组归属统计。反馈性能问题时请附上这几行。

## 构建

```bash
# 模组：所有 Minecraft 版本 × 加载器，产物在 mod/build/libs/<版本>/
cd mod && ./gradlew buildAndCollect
```

```bash
# OBS 插件（macOS，本机编译）
cmake -S obs-plugin -B obs-plugin/build-macos -DCMAKE_BUILD_TYPE=RelWithDebInfo && cmake --build obs-plugin/build-macos
```

```bash
# OBS 插件（Windows，从 macOS/Linux 用 MinGW 交叉编译；OBS_DIR 下需有 bin/64bit/obs.dll）
cmake -S obs-plugin -B obs-plugin/build-windows -DCMAKE_TOOLCHAIN_FILE=cmake/mingw-w64-x86_64.cmake -DOBS_DIR=<obs 安装目录> && cmake --build obs-plugin/build-windows
```

在 Windows 上也可以直接用 MSVC + CMake 构建；Linux 上装好 OBS 官方 PPA 的 `obs-studio`（自带 `libobs` 头文件）后直接 `cmake -S obs-plugin -B obs-plugin/build-linux && cmake --build obs-plugin/build-linux`。CMake 会自动下载对应版本的 obs-studio 头文件和 SIMDe。

## 测试

- `./gradlew :26.2-fabric:runClientGameTest -Playercast.forceAll=true -Playercast.splitAll=true`：Fabric 客户端游戏测试，自动建世界、打开 F3/Tab、显示 Boss 条/计分板/标题/聊天，把每个图层导出为 PNG（`run/fabric/layercast-test/`），并用 MaLiLib 热键打开配置界面。
  - `-Playercast.test.holdSeconds=N`：保持场景 N 秒，供 OBS 端检查；`-Playercast.test.resize=true`：中途改变窗口尺寸
  - `-Playercast.graphicsBackend=vulkan`：强制 Vulkan 后端；`-Playercast.transport=cpu`：强制 CPU 路径
  - `-Playercast.test.benchmark=true`：运行性能基准；`-Playercast.profile=true` 同时输出各阶段耗时，`-Playercast.test.fpsLimit=120` 按指定帧率上限测量（默认不限帧），`-Playercast.jfr=<文件>` 录制 Java Flight Recorder
  - `-Playercast.splitAll=true`：把所有部分（包括识别到的模组 HUD）都拆分出来；不加时只有 `game`
  - `-Playercast.testMods=minihud:K5zZmb6o,xaeros-minimap:VlMbRW2O`：额外加载 Modrinth 上的模组（`slug:版本ID`）做兼容性测试；场景里会关闭 F3 再导出一次（`layers-plain/`），并打开物品栏导出（`layers-inventory/`）；与 `holdSeconds` 同时使用时，还会在中途把拆分全部合并回去（`layers-merged-back/`），用来检查 OBS 端的列表更新
- `./gradlew :26.2-fabric:runClientGameTest -Playercast.forceAll=true -Playercast.test.matrix=true`：拆分矩阵测试。在 6 个场景（普通 HUD、F3、聊天界面、物品栏、南瓜头、名字标签）中分别导出"都不拆分 / 每个部分单独拆分（含识别到的模组 HUD）/ 全部拆分"时的图层，每次导出都附带同一帧的游戏最终画面 `_frame.png`；再用 `tools/check_layers.py <导出目录>` 检查 `game` + 各拆分图层叠起来是否与游戏画面逐像素一致（缺失的元素会被标出）。名字标签场景有空地上的名字、墙后只露出穿墙部分的名字、地下且一半被手持物品挡住的名字；该场景还会在隐藏名字后再导出一次（`nametags/_hidden`），检查工具据此确认拆分名字标签后 `game` 里确实没有名字（`left-in-game=0`）
- `./gradlew :26.2-neoforge:runClientTest`：NeoForge 版的同一场景（NeoForge 没有客户端游戏测试框架，由开发用测试模组驱动），同样支持 `-Playercast.testMods`。
- [`tools/obs_e2e.py`](tools/obs_e2e.py)：通过 obs-websocket 在 OBS 中建立测试场景并截取每个来源的画面。
- [`tools/lc_status.py`](tools/lc_status.py)：列出本机正在共享的频道（`default`、`default-2`……）、生产者 PID 和每个图层的状态/帧号，`--watch` 持续刷新；用来检查多个游戏是否各用各的频道。
- [`tools/win.sh`](tools/win.sh)、[`tools/win-sync.sh`](tools/win-sync.sh)：通过 SSH 在 Windows 测试机上同步代码、在桌面会话中启动 OBS/游戏；[`tools/linux.sh`](tools/linux.sh) 对 Linux（X11）测试机做同样的事。
- 游戏测试会关闭垂直同步（测试不需要它，而显示器休眠时它会把游戏拖到约 1 FPS）。

## 已知限制

- **Linux**：目前只有 CPU 共享内存路径（Minecraft 在 X11 下使用 GLX 上下文，无法直接导出 DMA-BUF），功能完整，但每帧要把图层经 PCIe 读回内存：1385×831 下每个图层约 0.34 ms GPU 时间，不限帧时典型拆分约少 8% 帧率、全部拆分约少 20%（Windows/macOS 的零拷贝路径没有这部分开销）。Vulkan 后端的 DMA-BUF 零拷贝路径已预留协议字段，尚未实现。
- **Linux + NVIDIA**：显示器休眠或锁屏时，驱动会把开着垂直同步的窗口限制到约 1 FPS（与本模组无关，关掉垂直同步即恢复）。
- JourneyMap 的 Fabric 版在 NVIDIA 显卡的 Linux 与 Windows 笔记本上启动即崩溃（`Missing uniform Globals`），去掉本模组的全部 mixin 后依旧，是 JourneyMap 自身的问题；NeoForge 版正常。
- **双显卡笔记本**：若 OBS 与游戏运行在不同 GPU 上，共享纹理无法跨显卡打开，会自动降级为 CPU 路径（功能正常，占用更多内存带宽；实测切换在 0.1 秒内完成）；把两者设为同一 GPU 可恢复零拷贝。NVIDIA Optimus 笔记本上 Java 默认跑在独显上，OBS 默认也选独显，一般不会遇到。
- Minecraft 的 Vulkan 后端本身仍是实验性的。
- 第一人称手臂和 F3 的 3D 坐标轴准星属于世界渲染，始终在 `game` 里。
- **名字标签**：
  - OBS 里 `nametags` 来源放在 `game` 之上，所以名字会盖在留在 `game` 里的 HUD（快捷栏、准星、聊天等）上面，而游戏里 HUD 在名字上面；需要时把重叠的 HUD 部分也拆分出来，放在 `nametags` 之上。
  - 原版在名字之后才画水、玻璃、云、雨和粒子。`game` 里名字所在的位置用"画名字之前的世界"填补，这些后画的东西的效果由名字周围的像素估算：没有它们时完全准确；有时是平滑的近似（例如名字后面是水面或云，隐藏名字后原来的位置看不出痕迹，只是雨丝略有模糊）。
  - 每次向 OBS 送出图层时多约 0.2 ms CPU（macOS M5 OpenGL，只在拆分了名字标签时才有）。
  - Minecraft 26.3 把名字标签和世界中的其他文字（告示牌等）合在一起绘制，暂不提供 `nametags` 图层。
- `game` 在有拆分时是重绘的：若此时打开了带背景模糊的菜单（暂停菜单、设置），`game` 里的世界不会被模糊。
- 原版准星是"反色"绘制（颜色取决于下面的画面）。留在 `game` 里时完全正确；单独拆出后透明图层无法重现，在 OBS 中给准星来源设置混合方式「差值 (Difference)」可以还原。
- OBS 按整层叠放：如果只拆出夹在其他元素中间的部分（例如南瓜头遮罩在快捷栏下、聊天在快捷栏上），两者重叠的几个像素只能整体在上或在下。
