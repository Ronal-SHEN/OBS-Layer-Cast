package dev.layercast.fabric;

//? if fabric {
import dev.layercast.LayerCast;
import dev.layercast.config.LayerCastInit;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;

public final class LayerCastFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LayerCast.init(new FabricPlatform());
        InitializationHandler.getInstance().registerInitializationHandler(new LayerCastInit());
    }
}
//?}
