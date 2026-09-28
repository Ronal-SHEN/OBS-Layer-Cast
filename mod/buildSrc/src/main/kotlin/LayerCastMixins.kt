/**
 * Mixin classes per Minecraft version. The mixin config is generated at build time so every version only lists
 * mixins whose targets exist (a missing target would abort game start).
 */
object LayerCastMixins {
    /**
     * @param fabric       Fabric scopes the hotbar with a mixin; NeoForge uses its GUI layer events instead
     * @param hudClass     26.2+ has a dedicated `Hud` class; 26.1 extracts the HUD in `Gui`
     * @param vulkan       the Vulkan backend exists (26.2+)
     * @param featureSets  Vulkan device extensions are declared through `VulkanFeatureSets` (26.3+)
     * @param nameTags     name tags have a renderer of their own, `NameTagFeatureRenderer` (26.1, 26.2; 26.3 draws them
     *                     with all other text)
     */
    fun client(fabric: Boolean, hudClass: Boolean, vulkan: Boolean, featureSets: Boolean, nameTags: Boolean): List<String> = buildList {
        addAll(listOf(
            "GameRendererMixin", "GuiMixin", "GuiRendererInternals", "GuiRendererMixin", "GuiRenderStateMixin",
            "PictureInPictureRendererInvoker", "PictureInPictureRendererMixin",
            "scope.ChatComponentScopeMixin", "scope.DebugScreenOverlayScopeMixin", "scope.ScreenScopeMixin",
            "scope.SubtitleOverlayScopeMixin", "scope.ToastManagerScopeMixin",
        ))
        // HudScopeMixin targets Hud (26.2+) or Gui (26.1) through a Stonecutter conditional.
        add("scope.HudScopeMixin")
        if (fabric) add("scope.HotbarScopeMixin")
        if (nameTags) {
            add("NameTagRendererMixin")
            // 26.1 batches name tags in a buffer source; 26.2 draws them in groups.
            if (!hudClass) add("NameTagStorageAccessor")
        }
        if (vulkan) {
            add("vulkan.GpuDeviceAccessor")
            add("vulkan.VulkanCommandEncoderInvoker")
            add(if (featureSets) "vulkan.VulkanFeatureSetsMixin" else "vulkan.VulkanBackendMixin")
        }
    }

    fun json(mixins: List<String>): String = mixins.joinToString(",\n    ", "[\n    ", "\n  ]") { "\"$it\"" }
}
