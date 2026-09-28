# OBS Layer Cast — 架构与 GPU 画面传递原理

本文记录实现前对 Minecraft 26.2 渲染管线、GPU 纹理跨进程共享以及 OBS 渲染模型的调研结论，以及据此确定的架构。
所有结论都基于 26.2 反编译源码（Loom `genSources`，Mojang 官方命名）、OBS 32.2.2 源码和本机实测。

---

## 1. Minecraft 26.2 的渲染管线

### 1.1 Blaze3D 抽象层与双后端

26.2 的全部渲染都经过 `com.mojang.blaze3d.systems.GpuDevice` / `CommandEncoder` / `RenderPass` 抽象：

| 抽象 | OpenGL 实现 | Vulkan 实现 (实验性) |
|---|---|---|
| `GpuDevice` | `GlDevice` | `VulkanDevice` |
| `GpuTexture` | `GlTexture`（`glId()`） | `VulkanGpuTexture`（`vkImage()`，VMA 分配） |
| `CommandEncoder` | `GlCommandEncoder`（直接发 GL 调用） | `VulkanCommandEncoder`（录制到 command buffer，`submit()` 时提交，最多 2 帧在途） |
| `GpuFence` | `GlFence`（`glFenceSync`） | 基于 timeline semaphore 的 submit index |

- 默认后端仍是 OpenGL 3.3 core（macOS 上为 4.1 core），视频设置里可切到 Vulkan（macOS 下经 MoltenVK）。Mojang 已宣布 Vulkan 稳定后会移除 OpenGL，因此 **共享层必须同时支持两种后端**。
- Vulkan 后端所有图像始终处于 `VK_IMAGE_LAYOUT_GENERAL`，每个 copy/pass 后插入全局 memory barrier——这意味着我们可以在它的 command buffer 里安全地追加 `vkCmdCopyImage`。
- `VulkanBackend.createDevice` 用一个固定的扩展集合创建 `VkDevice`，没有启用 external memory 扩展；Vulkan 路径需要通过 mixin 在创建设备前追加 `VK_KHR_external_memory_win32` / `VK_EXT_metal_objects` / `VK_KHR_external_memory_fd` 等扩展。

### 1.2 "提取 → 渲染" 两阶段的 GUI

自 1.21.6 起 GUI 不再即时绘制，26.2 进一步把它改名为 *extract*：

```
Minecraft.runTick
 ├─ GameRenderer.extract()
 │    └─ Gui.extractRenderState()                    ← 所有 GUI 只"登记"绘制请求
 │         ├─ Hud.extractRenderState()               ← 准星/快捷栏/Boss条/计分板/聊天/Tab…
 │         ├─ Screen.extractRenderStateWithTooltipAndSubtitles()
 │         ├─ ToastManager.extractRenderState()
 │         └─ Hud.extractDebugOverlay()              ← F3
 │      写入 GuiRenderState（节点树：stratum → 按包围盒自动分层）
 └─ GameRenderer.render()
      ├─ 清空 main target，renderLevel()            ← 3D 世界
      ├─ 后处理
      ├─ clearDepth
      └─ GuiRenderer.render()                        ← prepare(PiP/物品图集/文字字形) → 上传顶点 → 一次性绘制到 main target
```

关键事实：

1. `GuiRenderState` 里的元素（`BlitRenderState`、`GuiTextRenderState`、`GuiItemRenderState`、`PictureInPictureRenderState` …）都是**已烘焙好 pose/scissor 的不可变描述对象**，可以被多个 `GuiRenderState` 同时引用。
2. `GuiRenderer.draw()` 直接取 `gameRenderer.mainRenderTarget()` 作为输出，不看 `RenderSystem.outputColorTextureOverride`。
3. `GuiRenderer` 自带 `StagedVertexBuffer`（带 fence 的缓冲池）、物品图集、PiP 渲染器；**一个实例每帧只应 render 一次**，否则缓冲池会在同一帧内反复 `endFrame()` 导致缓冲区频繁重建。
4. PiP（画中画：物品栏里的玩家模型、小地图等）在 render 阶段先由对应的 PiP 渲染器画进它自己的纹理，再往 `GuiRenderState` 里补一个 blit 该纹理的元素。
5. Fabric API 的 `HudElementRegistry` 与 NeoForge 的 `GuiLayerManager`/`RenderGuiLayerEvent` 都以 ID 标识每个 HUD 元素，是跨版本最稳定的"元素边界"来源；F3、Screen、Toast 不在这两套注册表里，需要公共 mixin。

### 1.3 由此得出的图层捕获方案："单次提取 + 分流记录 + 独立重放"

排除的方案：

| 方案 | 问题 |
|---|---|
| 每个图层把 HUD 元素的 extract 再调用一次 | 部分 extract 有副作用（如 `extractAirBubbles` 会播放气泡破裂音效、`PlayerTabOverlay` 更新心跳状态），重复调用会产生可闻/可见的错误，并且 CPU 开销翻倍 |
| 在主画面绘制时读回像素再抠图 | 无法分离重叠元素，且需要 GPU→CPU 同步读回，必然卡顿 |
| 改写 `GuiRenderer` 让它按标签过滤绘制 | 侵入 `GuiRenderer` 内部排序/合批逻辑，跨版本极脆弱 |

采用的方案：

```
extract 阶段（每个元素只提取一次）
  原版部分的边界（公共 mixin / NeoForge RenderGuiLayerEvent）→ LayerCapture.enter(部分) … exit()
  其余位置（HUD 根、原版 GUI 之后、模组的 NeoForge GUI 层）→ "未认领"作用域，按绘制它的模组归属
  GuiRenderStateMixin：主 GuiRenderState 每收到一个元素，把**同一个对象引用**登记到恰好一个图层：
     该部分/模组已拆分 → 它自己的图层；否则 → game 图层
render 阶段
  原版 GuiRenderer.render() → 玩家看到的画面完全不变
  对每个激活图层：该图层专属的 GuiRenderer 执行 render() 的各个步骤 → 共享的透明离屏 RenderTarget
     （GuiRendererMixin 仅对图层渲染器把输出目标重定向到离屏目标，并跳过模糊；
       PiP 元素不重画，直接 blit 原版本帧已经画好的纹理）
  GPU 拷贝离屏目标 → 该图层当前的共享纹理槽位
```

- 玩家本地画面零改动；元素的提取（CPU 大头）只发生一次，副作用不重复。
- 图层按需渲染：只有 OBS 正在使用（心跳订阅）或配置强制开启的图层才会被记录和绘制；未被使用时开销只是一次布尔判断。
- 离屏目标所有图层共用一张（逐层"清空→绘制→拷贝"，GPU 命令天然有序），显存占用与图层数量无关；深度缓冲同样共用。
- 图层帧率独立于游戏帧率（默认上限 60 FPS），游戏跑 300 FPS 时不会多做 5 倍无用功。
- `game` 图层（完整画面减去已拆分的部分，不透明）：什么都不拆分时，直接拷贝 GUI 画完后的 main target；有拆分时，在原版 GUI 开始绘制前（其他模组挂在 `GuiRenderer.render()` 开头的内容也已画完）把 main target 拷贝到 game 自己的目标里，再把所有未拆分的 GUI 元素按原版顺序画上去。因为底下是真实的世界画面，暗角的乘法混合、准星的反色都和游戏里完全一样。

### 1.4 拆分（减法）模型

每个元素只进入一个图层：拆分出来的部分/模组有自己的图层，其余全部留在 `game` 图层，所以 `game` 加上各拆分图层叠起来正好等于完整画面。默认什么都不拆分，只有 `game`（即游戏画面本身）。

- 一个已拆分的图层本帧不渲染（图层按帧率错开）时，它的元素直接丢弃，不会掉回 `game`。
- `nextStratum` 转发给所有正在记录的图层，每个图层都保持原版的层叠顺序。
- 协议里未拆分的图层仍占着槽位和 id，状态为 `STATE_EMPTY`：OBS 插件只列出非 EMPTY 的图层；指向未拆分图层的来源显示空白、保留设置，重新拆分后自动恢复。拆分/合并时递增 `directory_seq`。
- 模组图层在运行中注册（第一次见到某模组画 HUD 时），占用下一个空槽位（共 32 个，内置 16 个：`game`、14 个原版 HUD 部分和名字标签；26.3 没有名字标签，为 15 个），并写入配置文件，下次启动时从配置直接注册。

**模组归属**（`ModAttribution`）：对"未认领"作用域里的元素，沿调用栈从元素往外走，直到回到调用模组代码的原版代码为止：

- 类 → 模组：按哪个模组的 jar 包含这个类（Fabric `ModContainer.findPath`，NeoForge `JarContents.containsFile`），结果按类缓存。
- 合并进原版类的 mixin 处理器方法名里带模组 id（`handler$abc000$modid$name`）；只有在栈上找不到模组、或只找到库时才需要读方法名。
- 栈上有多个模组时，被另一个模组依赖（required）的是库（MaLiLib、Architectury、xaerolib……），元素归依赖它的模组；多个互不依赖时取最外层（被原版调用的那个）。
- NeoForge 模组注册的 GUI 层直接按层 ID 的命名空间归属，不需要走栈。
- 开销控制：只有存在已拆分的模组图层、且本帧要渲染 `game` 或某个模组图层时才归属；平时每秒只抽查一帧用于发现新模组。栈遍历用 `DROP_METHOD_INFO`（只取类），调用点属于"不是任何模组的库"的模组、且完整遍历确认过归属时，该类以后直接查缓存。实测每个元素 2.3 µs（macOS M5）/ 4.7 µs（Windows）。

### 1.5 与其他模组的兼容

其他模组的 HUD 只要通过 `GuiGraphicsExtractor`/`GuiRenderState` 登记元素（26.x 下几乎只有这一条路），就会被分流到图层里；需要注意的是**登记发生在哪里、以及其他模组对同一处代码的挂钩**：

| 规则 | 原因 / 例子 |
|---|---|
| 记录窗口从 `Gui.extractRenderState` 开头一直开到 `GameRenderer.extract` 返回 | MaLiLib 在 `Gui.extractRenderState` 的 TAIL 派发 HUD 事件（MiniHUD、Tweakeroo 等在这里绘制）。以前在同一方法的 RETURN 结束记录，而同一个 return 指令前的多个注入谁先执行取决于 mixin 应用顺序，MiniHUD 因此被漏掉 |
| 原版在 `Gui.extractRenderState` 最后调用的是 `applyCursor`；在它之前打开一个"未认领"作用域 | 之后登记的元素全部来自其他模组 |
| 整个 `Hud.extractRenderState` 是一个"未认领"作用域，原版各组件在其中再开更具体的作用域（最内层优先） | 未被原版组件认领的 HUD 元素（Fabric `HudElementRegistry`、NeoForge 模组 GUI 层）按模组归属 |
| 作用域 mixin 优先级 1500（高于默认 1000） | `@WrapMethod` 按优先级嵌套，高优先级在最外层，于是其他模组对某个原版组件方法的注入/包装（例如取消原版准星后自己画、在状态效果图标上叠计时）都落在该组件的图层里 |
| 字幕作用域例外：用方法体内的 HEAD/RETURN 注入，`extractDeferredSubtitles` 整体是"未认领" | Fabric API 用 `@WrapMethod` 包住 `SubtitleOverlay.extractRenderState` 来绘制所有 `HudElementRegistry.addLast` 的元素；方法包装总是包在注入外面，这样模组 HUD 不会被归进 `subtitles` |
| 聊天界面（`ChatScreen` 及子类）整体属于 `chat`，不属于 `screen` | 聊天消息本来就在聊天作用域里，但输入框和命令补全是界面画的；以前拆分 `chat` 后，空的输入框会留在合并的图层里 |
| 暗角（vignette）在透明图层里换成"黑色 + 透明度"的着色器（`game` 里保持原样） | 原版用 `dst × (1 − src)` 的混合让下面的画面变暗；透明图层下面没有东西可以变暗，暗角会整个丢失。灰度暗角换算成黑色、alpha = 强度后叠加结果完全相同（世界边界的红色警告暗角只能近似）。着色器按版本有两份（26.3 的 uniform 布局不同） |
| `addBlitToCurrentLayer` / `addGlyphToCurrentLayer` 也要分流 | JourneyMap 在提取阶段把地图画进自己的纹理，再用 `addBlitToCurrentLayer` 贴到界面上 |
| 图层不重画 PiP，而是 blit 原版本帧画好的纹理（记录"哪个 PiP 渲染器为哪个状态 blit 过"） | Xaero 小地图的 PiP 渲染器给状态打"已准备"标记、同一状态只画一次；同时省掉每个图层重复渲染实体预览、小地图的 GPU 开销 |
| 图层不调用 `GuiRenderer.render()`，而是依次调用它内部的步骤（`RenderCompat.renderGui`） | 其他模组把 `render()` 的 HEAD/RETURN 当作"原版 GUI 绘制前后"的事件（Xaero 在这里把世界内路径点直接画进 main target），不能让它们为每个图层再执行一次、污染玩家画面 |

仍然无法拆分的情况：模组绕过 `GuiRenderState`、直接向 main target 绘制的内容（例如 Xaero 在 `GuiRenderer.render()` 开头画的世界内路径点标记）不属于任何拆分图层；`game` 在它们画完之后才拷贝世界画面，所以它们留在 `game` 里。这类内容画在世界里，本来就不算屏幕 HUD。

### 1.6 名字标签：从世界画面里拆出的图层

名字标签（`nametags`，`Layer.Kind.WORLD`）不是 GUI 元素，而是 `LevelRenderer` 在世界的半透明阶段画的：26.2 由 `NameTagFeatureRenderer` 按组绘制（穿墙的半透明部分一组、其余一组，按距离排序，顶点在整帧准备阶段就已上传），26.1 由它往批处理 `BufferSource` 里写顶点。在它们之后，原版还要画半透明方块（水、玻璃）、粒子、云、雨和第一人称手臂，所以不能像 GUI 那样"只记录、事后重放"，也不能改变玩家看到的画面。做法（`NameTagLayer`）：

1. **画第一组名字之前**：把 main target 的颜色拷贝一份（"画名字之前的世界"），把深度拷贝到名字图层的目标里，清空图层颜色。
2. **原版每画一组名字（照常画进 main target）**，把同一组再画一次：临时设置 `RenderSystem.outputColorTextureOverride` / `outputDepthTextureOverride`，让这次绘制写进名字图层（透明背景）并测试/写入拷贝来的深度，因此被方块挡住的部分、穿墙可见的半透明部分都与游戏里一致。26.2 直接对同一组调用第二次 `executeGroup`；26.1 先把名字之前待画的批次画掉（原版在第一个名字处本来也会画），再生成名字的顶点并立即 `endLastBatch()`，游戏与图层各一次。
3. **`GameRenderer.renderLevel` 结束时**（手臂已画完）：原版在画手臂前清空了深度，此时深度缓冲里只有手臂，把它拷贝到图层的深度里。
4. **GUI 开始绘制前**（与 `game` 拷贝世界画面同一时刻），两个全屏 pass：
   - 从 `game` 中去掉名字：图层有内容且没被手臂挡住的像素，用"画名字之前的世界"替换，再加上之后画的东西（水、云、雨、粒子）在这里的效果。这个效果无法从最终画面里还原（不透明的文字写了深度，它后面的水、云根本不会被画出来），所以用名字周围的像素估算：沿 8 个方向找最近的名字外像素（步长逐渐变大，靠近镜头的名字很大），取"最终画面 − 画名字之前的世界"的平均值。没有后画的东西时完全准确，有时是平滑的近似。最初试过的两种方案都有明显痕迹：直接用"画名字之前的世界"会在水面、云前留下没有水、没有云的矩形；按图层 alpha 反推（`(frame − tags) / (1 − alpha)`）会把画在背景板上的水、云放大 1/(1 − 0.25) 倍，留下一块偏亮的矩形。
   - 从名字图层中擦掉被手臂挡住的部分（深度不是清空值的像素）。

这两个 pass 采样的是 D32 深度纹理和颜色纹理，着色器是 `layercast:core/name_tag_game` / `name_tag_mask`，顶点着色器用原版的 `core/screenquad`；"清空后的深度值"按版本以 shader define 传入（26.2 起为 0，26.1 为 1）。

只有拆分了名字标签、且本帧要送出 `game` 或 `nametags` 时才做这些；`game` 需要名字的位置才能去掉它们，所以只有 OBS 只看 `game` 时也会在内部生成名字图层（但不送出）。仍然有的限制：OBS 按整层叠放，名字图层在 `game`（含未拆分的 HUD）之上；不透明文字前面有后画的东西（例如名字前面的水）时，叠加结果里文字不再被它染色。

26.3 没有 `NameTagFeatureRenderer`：名字标签变成普通的 `TextFeatureRenderer` 提交，不透明文字进入实体的实心阶段，和告示牌等其他文字合并成同一批绘制，还可能进入新的顺序无关透明（OIT）阶段。要在那里拆分，需要在准备阶段把名字标签的顶点拆成独立的 draw，改动很深，所以 26.3 暂不注册 `nametags` 图层（`Layers.NAME_TAGS` 为 `null`）。

---

## 2. GPU 纹理跨进程共享原理

### 2.1 为什么必须是 GPU 共享

1080p RGBA8 一帧 8.3 MB。若走 `glReadPixels` 同步读回，GPU 管线必须排空（CPU 等 GPU 完成前面所有命令），直接导致帧时间尖刺；即使用 PBO 异步读回，每层每秒 60 帧也要 0.5 GB/s 的 PCIe 往返带宽，外加 OBS 侧再上传一次。
GPU 共享让两个进程引用**同一块显存**：生产者只做一次 GPU→GPU 拷贝（1080p 约 20–60 µs），消费者直接采样，全程不经过 CPU 内存，也不引入 CPU/GPU 同步点。

### 2.2 各平台机制

| 平台 | 共享对象 | 生产者 (MC, OpenGL) | 生产者 (MC, Vulkan) | 消费者 (OBS) |
|---|---|---|---|---|
| Windows | D3D11 共享纹理 | 自建 D3D11 设备（按 GL 的 adapter LUID 选同一块 GPU）→ `CreateTexture2D(MISC_SHARED)` → `WGL_NV_DX_interop2` 把它注册成 GL 纹理 → 每帧一次 lock（本帧要写的所有槽位）/ `glCopyImageSubData` / 一次 unlock | D3D11 纹理 `MISC_SHARED_NTHANDLE` → `VK_KHR_external_memory_win32` 导入为 `VkImage` → `vkCmdCopyImage` | `gs_texture_open_shared(KMT handle)` / `gs_texture_open_nt_shared` |
| macOS | IOSurface | `IOSurfaceCreate(kIOSurfaceIsGlobal)` → `CGLTexImageIOSurface2D` 绑定为 `GL_TEXTURE_RECTANGLE` → FBO blit | `VK_EXT_metal_objects` 以 IOSurface 为后备创建 `VkImage` → `vkCmdBlitImage` | `IOSurfaceLookup(id)` → `gs_texture_create_from_iosurface`（OpenGL 与 Metal 渲染器都实现了） |
| Linux | DMA-BUF（**规划中，尚未实现**；目前走 CPU 兜底） | EGL 上下文：`EGL_MESA_image_dma_buf_export`；GLX 上下文无法导出 | `VK_EXT_external_memory_dma_buf` | `gs_texture_create_from_dmabuf` |
| 任意 | 共享内存（兜底） | `CommandEncoder.copyTextureToBuffer`（MC 自带的异步读回，fence 完成后回调，不阻塞） | 同左 | `gs_texture_set_image` |

说明：

- **Windows 的 `WGL_NV_DX_interop` 路径与 OBS 自身 "游戏捕获" 对 OpenGL 游戏的 hook 完全一致**（`plugins/win-capture/graphics-hook/gl-capture.c`），NVIDIA/AMD/Intel 驱动都支持，是经过大规模验证的路径。
- **D3D11 设备必须与 GL 上下文在同一块 GPU 上**。双显卡笔记本上 GL 往往在独显、默认 D3D 适配器是核显，必须通过 `GL_EXT_memory_object` 的 `GL_DEVICE_LUID_EXT` 找到正确的 DXGI adapter。若 OBS 与 MC 不在同一块 GPU（跨适配器无法打开共享句柄），消费者会把失败写回目录，生产者自动把该图层降级为 CPU 共享内存传输。
- macOS 的 global IOSurface（`kIOSurfaceIsGlobal`）虽被标记为 deprecated，但在 macOS 26.5 上实测仍可跨进程 `IOSurfaceLookup`；Syphon 也依赖同一机制。OBS 自带的 `gs_texture_open_shared` 在 macOS 上期望的是 mach port，因此插件直接用 `IOSurfaceLookup` + `gs_texture_create_from_iosurface`。
- 不在生产端直接渲染进共享纹理，而是"MC 自己的纹理 → 一次拷贝 → 共享纹理"：
  - 共享纹理的格式/类型受限（IOSurface 在 GL 中只能是 `GL_TEXTURE_RECTANGLE` 且 BGRA；interop 纹理在 GL 使用期间必须保持 lock），不能直接当作 `GlTexture` 给原版管线使用；
  - OBS 永远只会看到**完整的一帧**，不会看到绘制到一半的内容；
  - 一次整帧拷贝的 GPU 成本可以忽略。

### 2.3 同步：既不撕裂也不阻塞

```
生产者（每个图层 3 个共享槽位的环形缓冲）
  帧 N:   拷贝 → 槽位 (k+1)%3，插入 GpuFence
  帧 N+1: fence.awaitCompletion(0)  ← 零超时轮询，永不阻塞渲染线程
          完成 → 目录中 published_slot = (k+1)%3, seq++（seqlock 发布）
消费者（OBS 图形线程）
  video_tick:   读取 seq/slot（seqlock 校验），必要时重新打开句柄
  video_render: 采样 published_slot
```

- 只有 GPU 已经执行完毕的槽位才会被发布，所以 OBS 读到的一定是完整帧。
- 生产者写入的永远是"下一个"槽位；OBS 正在读的槽位至少要再经过两次发布才会被覆盖，而图层帧率又被限制在 OBS 帧率附近，覆盖窗口远大于 OBS 渲染一帧的时间。
- 消费者把正在读的槽位和心跳写回目录，生产者会跳过被标记占用的槽位（CPU 级提示，双重保险）。
- 整个过程中没有任何一方调用阻塞式等待（无 `glFinish`、无 `vkQueueWaitIdle`、无 keyed mutex 等待），因此不会把一方的卡顿传染给另一方。

### 2.4 每帧开销与调度

- **同一帧捕获所有到期图层**。每个图层按自己的目标帧率（OBS 帧率与配置上限的较小值）到期；只要有图层到期，本帧就捕获所有已到期的图层，差不到四分之一个间隔就到期的图层也一起捕获，所以同帧率的图层始终步调一致。GUI 分流（包括模组归属）和共享纹理同步是按帧付费的，与图层数无关；OBS 拿到的各图层也来自同一帧画面，减法模型下 `game` 与拆分图层不会错帧。
- **Windows interop 批量加锁**。`wglDXLockObjectsNV` / `wglDXUnlockObjectsNV` 各自会阻塞渲染线程约 50–100 µs。GUI 渲染完成后、绘制各图层之前，`SharingService.reserveSlots` 先为每个到期图层选好槽位（`Transport.reserve`），第一次拷贝时把这些槽位一次性加锁，帧末（`endFrame`）一次性解锁并 `Flush` D3D11 上下文。只锁要写入的槽位，不碰 OBS 正在读或刚发布的槽位。拷贝用 `glCopyImageSubData`（无需绑定帧缓冲），驱动不支持时退回 `glBlitFramebuffer`。
- **空图层不重复发送**。分流时记录每个图层本帧是否收到元素（`LayerCapture.hasContent`）；拆分图层为空、且上一次送出的也是空帧时，跳过绘制和拷贝，只记作已捕获（`SharingService.skipEmpty`）。一旦出现内容就立即恢复。诊断导出（`LayerDebug`）时不跳过。
- **诊断**：`-Dlayercast.profile=true` 打开 `LayerProfiler`，按阶段（帧开始、GUI 提取、世界拷贝、`game` 重绘、拆分图层、传输、帧末）统计 CPU 时间，OpenGL（非 macOS）下用时间戳查询统计 GPU 时间，并区分共享帧与普通帧的帧间隔；平时每 10 秒写一行日志，基准测试直接读取。关闭时每个埋点只是一次静态常量判断。

### 2.5 Y 轴方向与颜色

- GL 纹理原点在左下，D3D/Metal/IOSurface 原点在左上；直接逐字节拷贝得到的图像是上下颠倒的。生产者不做额外的翻转绘制，而是在目录里声明 `flip_y`，由 OBS 在绘制 sprite 时用 `GS_FLIP_V` 免费翻转。
- GUI 使用 `(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)` 混合到清空为 `(0,0,0,0)` 的目标上，结果天然是**预乘 alpha**。OBS 滤镜期望直通 alpha，因此插件使用 `DrawAlphaDivide` 技术反预乘后再交给 OBS 合成；`game` 图层声明为不透明，使用 `OBS_EFFECT_OPAQUE`。
- 所有数据保持 sRGB 编码的 8-bit 值（MC 与 OBS 的 SDR 管线一致），插件不声明 `OBS_SOURCE_SRGB`，避免在预乘数据上做线性化导致边缘发灰。

---

## 3. 进程间协议

一块命名共享内存作为"目录"（Windows `Local\LayerCast.v1.<channel>`，POSIX `/layercast.v1.<channel>`），固定布局、小端、所有字段按自然对齐：

- 头部：magic、协议版本、生产者 PID、会话 ID（每次启动随机）、心跳时间戳、渲染后端、生产者描述。
- 最多 32 个图层描述（内置 16 个，26.3 为 15 个 + 运行中识别到的模组图层）：`id`、显示名、状态（`EMPTY` = 未拆分、不提供给 OBS）、宽高、像素格式、传输方式、`flip_y`/不透明标志、槽位数、每个槽位的句柄（KMT handle / IOSurfaceID / CPU 帧段）、`generation`（句柄重建时递增）、seqlock 发布字段；以及消费者回写区（订阅心跳、正在读的槽位、打开失败的错误码）。

多个游戏共用一台电脑时，每个频道只属于一个游戏：

- 目录头部的生产者 PID 标明频道的主人。启动时若频道已被另一个**仍在运行的 Java 进程**占用（心跳有效且 PID 存活），就依次尝试 `<频道>-2` … `<频道>-9`，并在进入世界后用游戏内消息告诉玩家实际频道；主人已退出或崩溃的目录可以直接接管。
- 每帧检查自己是否仍是目录的主人；万一两个游戏同时抢到同一个频道，输的一方放弃该目录、改用下一个频道，不会与对方交替写入。退出时只有主人才会清空和删除目录。
- OBS 插件的「频道」是可编辑的下拉框，列出本机所有存活的频道及其生产者描述和 PID；`tools/lc_status.py` 在命令行列出同样的信息。

健壮性约定：

- 任一方都可能先启动、崩溃或重启：消费者以 `session_id` + 心跳判定生产者是否存活，超时即释放纹理并输出透明；生产者以消费者心跳决定是否继续渲染该图层，超时 10 s 后释放共享纹理。
- 所有从共享内存读到的索引、尺寸、句柄都做边界校验；打开句柄失败只记日志并回写错误，不会崩溃。
- 已被 OBS 打开的共享纹理由系统引用计数保活，生产者重建/退出不会让 OBS 访问到已释放的显存。

---

## 4. 代码结构

```
mod/                              Gradle + Stonecutter：每个 (MC 版本 × 加载器) 是一个节点，共享同一份源码
  settings.gradle.kts             版本/加载器矩阵
  stonecutter.properties.toml     各版本依赖（Fabric API、MaLiLib/MaFgLib、NeoForge…）
  stonecutter.gradle.kts          26.3 的包重定位（blaze3d → renderpearl）字符串替换
  buildSrc/…/LayerCastMixins.kt   按版本/加载器生成 mixin 列表
  src/main/java/dev/layercast/
    LayerCast                     各挂钩点的入口；任何异常都只禁用本模组，绝不让游戏崩溃
    LayerProfiler                 可选的分阶段耗时统计（-Dlayercast.profile=true）
    layer/                        Layers（内置 + 模组图层表）、LayerCapture（作用域栈 + 单一去向分流）、ModAttribution（按调用栈判断模组）、LayerRenderer（独立 GuiRenderer + 共用离屏目标）、NameTagLayer（名字标签：第二次绘制、从 game 中去掉、手臂遮挡）、LayerPipelines（图层专用管线）、LayerDebug（导出 PNG）
    share/                        Protocol/Directory（共享目录、频道归属）、SharingService（按需、同帧捕获、槽位预留、空图层跳过、fence 轮询、自动降级、频道自动编号）
    share/transport/              MacGlIOSurface / WinGlDxInterop / MacVkIOSurface / WinVkD3D11 / Cpu
    share/ffm/                    Java 25 FFM 绑定：共享内存、CoreFoundation/IOSurface/CGL、DXGI/D3D11 COM、WGL
    share/vk/                     访问 Minecraft 的 VkDevice / 当前 command buffer
    compat/RenderCompat           各 MC 版本间形状不同的少量渲染 API
    platform/LoaderPlatform       加载器差异（GuiRenderer 构造器：NeoForge 补丁后接收 PiP 渲染器注册表）
    config/                       MaLiLib 配置、配置界面、热键（Fabric 用 MaLiLib，NeoForge 用 API 相同的 MaFgLib）
    mixin/, mixin/scope/, mixin/vulkan/   挂钩点
    fabric/, neoforge/            加载器入口（`//? if fabric` / `//? if neoforge` 条件编译）
  src/gametest/                   Fabric 客户端游戏测试（端到端场景 + 性能基准）
  src/devtest/                    NeoForge 开发用测试模组（同一场景）
obs-plugin/                       C 插件（CMake；macOS 与 Linux 本机编译，Windows 用 MinGW 交叉编译）
  src/layercast-protocol.h        目录布局的唯一定义（Java 侧 Protocol.java 与之逐字段对应）
  src/layercast-source.c          来源实现：连接/重连、seqlock 读取配置、打开共享纹理、反预乘绘制
  src/lc-texture-{macos,windows,linux}.c   打开各平台共享句柄
  data/effects/                   反预乘 / 不透明绘制着色器（2D 与 GL rectangle 两个版本）
tools/                            OBS websocket 自动化、图层逐像素检查、频道状态查看、Windows 远程测试脚本
```

## 5. 多版本与多加载器

26.x 全部使用 Mojang 官方命名、都需要 Java 25，公共代码可以直接共享；但渲染相关 API 在相邻版本之间变化很大，这正是本项目对多版本架构的主要需求。实际遇到的差异与处理方式：

| 差异 | 26.1.x | 26.2 | 26.3 | 处理 |
|---|---|---|---|---|
| GPU 抽象的包名 | `com.mojang.blaze3d.*` | 同左 | `com.mojang.renderpearl.{api,backend}.*` | Stonecutter 字符串替换（点号与 JVM 描述符两种形式） |
| `GpuDevice` | 类 | 类 | 接口，实现为 `FrontendGpuDevice` | 访问器 mixin 的目标按版本切换 |
| Vulkan 后端 | 无 | 有，扩展表写死在 `createDevice` | `VulkanFeatureSets` 可选特性集 | 26.2 包装 `createDevice` 调用追加扩展；26.3 直接注册一个可选 FeatureSet，由 Minecraft 自己判断是否启用 |
| HUD 所在类 | `Gui` | `Hud` | `Hud` | 作用域 mixin 目标按版本切换，方法名不变 |
| GUI 提取入口 | `GameRenderer.extractGui` | `Gui.extractRenderState` | 同左 | 同一个 mixin，目标/方法名按版本切换 |
| `GameRenderer.render` 参数 | `(DeltaTracker, boolean)` | 同左 | `()` | 处理器只声明 `CallbackInfo`，与参数表无关 |
| 主渲染目标 | `Minecraft.getMainRenderTarget()` | `GameRenderer.mainRenderTarget()` | 同左 | `RenderCompat` |
| `GuiRenderer.render` 内部步骤 / 构造器 | `draw(fogBuffer)`、按顶点格式的 `MappableRingBuffer`，构造需 BufferSource 与 SubmitNodeCollector | `StagedVertexBuffer` 的 upload/endDraw/endFrame | 另有 `resizeAllAutoStorageIndexBuffers()` | `RenderCompat.renderGui` + `GuiRendererInternals` + `LoaderPlatform`（移植新版本时要对照原版 `render()`） |
| 深度清除值 | 1.0 | 0.0（反向 Z） | 0.0 | `RenderCompat.clearTransparent` |
| 窗口系统 | GLFW | GLFW | SDL3（键码变化） | 测试改用 `InputConstants` |

新增一个 Minecraft 版本的步骤：在 `settings.gradle.kts` 加节点、在 `stonecutter.properties.toml` 填依赖版本、编译，按报错把差异补进 `RenderCompat` / mixin 条件块 / `LayerCastMixins`，再跑 `runClientGameTest`（Fabric）或 `runClientTest`（NeoForge）。

加载器差异只有三处：入口与配置界面注册、`GuiRenderer` 的构造（`LoaderPlatform`）、快捷栏作用域（Fabric 用 mixin 包住 `extractHotbarAndDecorations`，NeoForge 用其 GUI 层事件，因为 NeoForge 把该方法拆成了多个层）。其余 HUD 组件的作用域都挂在两个加载器都保持不变的原版方法上。

## 6. 验证结果

| 场景 | 结果 |
|---|---|
| macOS · OpenGL / Vulkan(MoltenVK) → IOSurface → OBS（OpenGL 渲染器） | ✅ 画面、方向、透明度正确 |
| Windows · OpenGL → WGL_NV_DX_interop → D3D11 KMT 句柄 → OBS（D3D11） | ✅（自动匹配到同一块 GPU 的 LUID） |
| Windows · Vulkan → D3D11 NT 句柄 → OBS | ✅ |
| CPU 共享内存路径 | ✅ |
| OBS 打不开共享纹理 → 自动降级为 CPU | ✅（插件模拟失败，约 100 ms 内完成切换） |
| 窗口尺寸变化 | ✅ OBS 自动跟随新尺寸 |
| Minecraft 重启 / OBS 重启后重连 | ✅ |
| 按需渲染（只渲染 OBS 可见的图层） | ✅ |
| 26.1.2 / 26.2 / 26.3 × Fabric，26.1.2 / 26.2 × NeoForge 图层捕获 | ✅ |
| 第三方 HUD 模组按模组拆分（见 README 兼容性表） | ✅ 26.2 Fabric/NeoForge 全部；26.1.2、26.3 Fabric 与 26.1.2 NeoForge 回归；Windows OpenGL/Vulkan 同样结果 |
| 名字标签图层（macOS M5） | ✅ 26.2 Fabric（OpenGL 与 Vulkan）、26.1.2 Fabric、26.1.2 / 26.2 NeoForge：拆分矩阵的名字标签场景 0 个缺失像素，与隐藏名字后的画面相比 `game` 中 0 个名字像素残留；墙后名字只剩穿墙部分、手持物品挡住的部分被擦掉；OBS 中 `lc-nametags` 与游戏导出逐像素相同，合并回去后名字回到 `game`、来源变空白。水面与雨中的名字另做了目视检查。Windows、Linux 尚未测试 |
| 拆分矩阵：5 个场景 × {都不拆分、每个部分/模组单独拆分、全部拆分}，`game` + 拆分图层叠加与同一帧的游戏画面逐像素比较（`tools/check_layers.py`） | ✅ 0 个缺失像素（不拆分时 `game` 与游戏画面逐字节相同）：26.1.2 / 26.2 / 26.3 Fabric（OpenGL，26.2 另测 Vulkan）、Windows OpenGL / Vulkan、26.1.2 / 26.2 NeoForge；唯一的差异是整层叠放无法还原的重叠顺序和单独拆出的准星的反色 |
| OBS 只列出提供的图层、运行中拆分/合并 | ✅ macOS 与 Windows 的 OBS：不拆分时只有 game；全部拆分后列出 game、所有部分与模组图层，game 只剩世界画面；中途合并回去后列表立即收缩、对应来源变空白、game 恢复为完整画面 |
| 多个游戏同时运行 | ✅ macOS：Fabric 与 NeoForge 同时运行分别使用 `default` / `default-2`，帧号各自单调递增；模拟同时抢占时输的一方切换到下一个频道；崩溃留下的目录被接管；OBS 下拉框列出两个游戏，两个来源分别显示两个游戏。Linux 与 Windows（双显卡笔记本）同样通过：Windows 上游戏内提示改用的频道 |
| 双显卡：游戏在 NVIDIA、OBS 在 AMD 核显 | ✅ OBS 打不开共享句柄并回写错误，游戏把每个图层切换为 CPU 共享内存，约 35 ms 后 OBS 重新连上，画面逐像素一致 |
| 性能（Windows OpenGL，OBS 读取 5 个图层） | ✅ 限 120 FPS 时帧率、p99 帧时间与关闭时相同，无额外卡顿帧；详见 README |
| Linux（Ubuntu 24.04、X11、NVIDIA） | ✅ CPU 共享内存路径：OpenGL / Vulkan；26.1.2 / 26.2 / 26.3 Fabric 拆分矩阵与模组 HUD、26.1.2 / 26.2 NeoForge 全部 0 缺失像素；OBS 32.2 中逐像素一致、只列出提供的图层、合并回去后列表收缩；窗口尺寸变化、Minecraft/OBS 重启后重连；Fabric 与 NeoForge 同时运行分别用 `default` / `default-2`，OBS 下拉框列出两者 |

性能数据见 README。
