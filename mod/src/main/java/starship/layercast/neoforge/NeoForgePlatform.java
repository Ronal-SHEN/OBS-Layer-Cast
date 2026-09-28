package starship.layercast.neoforge;

//? if neoforge {
/*import starship.layercast.platform.LoaderPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

final class NeoForgePlatform implements LoaderPlatform {
    @Override
    public String name() {
        return "NeoForge";
    }

    @Override
    public GuiRenderer createGuiRenderer(GuiRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        // NeoForge patches the constructor to take picture-in-picture renderer registrations instead of renderers.
        //? if >=26.2 {
        return new GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), List.of());
        //?} else {
        /^return new GuiRenderer(state, minecraft.renderBuffers().bufferSource(), minecraft.gameRenderer.getSubmitNodeStorage(),
            minecraft.gameRenderer.getFeatureRenderDispatcher(), List.of());
        ^///?}
    }

    @Override
    public @Nullable String modIdOf(Class<?> type) {
        String file = type.getName().replace('.', '/') + ".class";
        for (IModFileInfo modFile : ModList.get().getModFiles()) {
            if (!modFile.getMods().isEmpty() && modFile.getFile().getContents().containsFile(file)) {
                return modFile.getMods().getFirst().getModId();
            }
        }
        return null;
    }

    @Override
    public String modName(String modId) {
        return ModList.get().getModContainerById(modId).map(mod -> mod.getModInfo().getDisplayName()).orElse(modId);
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Collection<String> modIds() {
        return ModList.get().getMods().stream().map(IModInfo::getModId).toList();
    }

    @Override
    public Collection<String> requiredDependencies(String modId) {
        return ModList.get().getModContainerById(modId)
            .map(mod -> mod.getModInfo().getDependencies().stream()
                .filter(dependency -> dependency.getType() == IModInfo.DependencyType.REQUIRED)
                .map(IModInfo.ModVersion::getModId)
                .toList())
            .orElse(List.<String>of());
    }
}
*///?}
