package dev.layercast.fabric;

//? if fabric {
import dev.layercast.platform.LoaderPlatform;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

final class FabricPlatform implements LoaderPlatform {
    @Override
    public String name() {
        return "Fabric";
    }

    @Override
    public GuiRenderer createGuiRenderer(GuiRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        //? if >=26.2 {
        return new GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), List.of());
        //?} else {
        /*return new GuiRenderer(state, minecraft.renderBuffers().bufferSource(), minecraft.gameRenderer.getSubmitNodeStorage(),
            minecraft.gameRenderer.getFeatureRenderDispatcher(), List.of());
        *///?}
    }

    @Override
    public @Nullable String modIdOf(Class<?> type) {
        String file = type.getName().replace('.', '/') + ".class";
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.findPath(file).isPresent()) {
                return mod.getMetadata().getId();
            }
        }
        return null;
    }

    @Override
    public String modName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getName()).orElse(modId);
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Collection<String> modIds() {
        return FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).toList();
    }

    @Override
    public Collection<String> requiredDependencies(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(mod -> mod.getMetadata().getDependencies().stream()
                .filter(dependency -> dependency.getKind() == ModDependency.Kind.DEPENDS)
                .map(ModDependency::getModId)
                .toList())
            .orElse(List.of());
    }
}
//?}
