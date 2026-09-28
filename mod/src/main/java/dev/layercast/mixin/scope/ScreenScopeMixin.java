package dev.layercast.mixin.scope;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.layercast.layer.LayerCapture;
import dev.layercast.layer.Layers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Open screens belong to the {@code screen} layer, except the chat screen: its input line, command suggestions and
 * the chat history it shows are all part of the chat.
 */
@Mixin(Screen.class)
abstract class ScreenScopeMixin {
    @WrapMethod(method = "extractRenderStateWithTooltipAndSubtitles")
    private void layercast$screen(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, Operation<Void> original) {
        LayerCapture.enter((Object) this instanceof ChatScreen ? Layers.CHAT : Layers.SCREEN);
        try {
            original.call(graphics, mouseX, mouseY, a);
        } finally {
            LayerCapture.exit();
        }
    }
}
