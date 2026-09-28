package starship.layercast.mixin.scope;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import starship.layercast.layer.LayerCapture;
import starship.layercast.layer.Layers;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;

/** Covers both the in-game chat and the chat drawn by the open chat screen. */
@Mixin(ChatComponent.class)
abstract class ChatComponentScopeMixin {
    @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V")
    private void layercast$chat(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY,
                                ChatComponent.DisplayMode displayMode, boolean changeCursorOnInsertions, Operation<Void> original) {
        LayerCapture.enter(Layers.CHAT);
        try {
            original.call(graphics, font, ticks, mouseX, mouseY, displayMode, changeCursorOnInsertions);
        } finally {
            LayerCapture.exit();
        }
    }
}
