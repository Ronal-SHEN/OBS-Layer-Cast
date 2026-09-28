package starship.layercast.fabric;

//? if fabric {
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import starship.layercast.config.GuiConfigs;

/** Opens the MaLiLib config screen from Mod Menu. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            GuiConfigs gui = new GuiConfigs();
            gui.setParent(parent);
            return gui;
        };
    }
}
//?}
