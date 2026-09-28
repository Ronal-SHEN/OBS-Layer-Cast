package dev.layercast.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum TransportModeOption implements IConfigOptionListEntry {
    AUTO("auto", LayerCastSettings.TransportMode.AUTO),
    GPU("gpu", LayerCastSettings.TransportMode.GPU),
    CPU("cpu", LayerCastSettings.TransportMode.CPU);

    private final String name;
    private final LayerCastSettings.TransportMode mode;

    TransportModeOption(String name, LayerCastSettings.TransportMode mode) {
        this.name = name;
        this.mode = mode;
    }

    public LayerCastSettings.TransportMode mode() {
        return this.mode;
    }

    @Override
    public String getStringValue() {
        return this.name;
    }

    @Override
    public String getDisplayName() {
        return StringUtils.translate("layercast.label.transport_mode." + this.name);
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        TransportModeOption[] values = values();
        int index = (this.ordinal() + (forward ? 1 : values.length - 1)) % values.length;
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        for (TransportModeOption option : values()) {
            if (option.name.equalsIgnoreCase(value)) {
                return option;
            }
        }
        return AUTO;
    }
}
