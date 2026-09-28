package dev.layercast.config;

import dev.layercast.LayerCast;
import dev.layercast.share.ObsPresence;
import dev.layercast.share.SharingService;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetHoverInfo;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-game configuration screen (MaLiLib). Tabs: general options, which GUI parts to split into their own layers
 * (vanilla components and every mod HUD found so far), hotkeys. On the general tab a status line tells whether the
 * LayerCast OBS plugin is running, next to a button to download it.
 */
public final class GuiConfigs extends GuiConfigsBase {
    /** Where the OBS plugin is downloaded from (the "Get OBS plugin" button). */
    public static final String OBS_PLUGIN_URL = "https://github.com/Ronal-SHEN/OBS-Layer-Cast/releases";

    private static final int TABS_Y = 26;
    private static final int STATUS_Y = 50;
    /** Where the list starts: below the status line on the general tab, right below the tabs otherwise. */
    private static final int LIST_Y_WITH_STATUS = STATUS_Y + 24;

    private static Tab tab = Tab.GENERIC;

    private final List<WidgetBase> statusWidgets = new ArrayList<>();
    private ObsPresence.@Nullable Report shownReport;

    public GuiConfigs() {
        super(10, listY(), LayerCast.MOD_ID, null, "layercast.gui.title.configs", LayerCast.VERSION);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        this.statusWidgets.clear(); // removed by super.initGui() along with every other widget
        this.buildHeader(tab == Tab.GENERIC ? ObsPresence.report() : null);
    }

    @Override
    public void tick() {
        super.tick();
        if (tab == Tab.GENERIC) {
            ObsPresence.Report report = ObsPresence.report();
            if (!report.equals(this.shownReport)) {
                this.buildHeader(report);
            }
        }
    }

    private static int listY() {
        return tab == Tab.GENERIC ? LIST_Y_WITH_STATUS : STATUS_Y;
    }

    /**
     * The tab buttons and the OBS status line (general tab only, {@code report} is {@code null} on the others);
     * rebuilt on its own when the status changes, leaving the list alone.
     */
    private void buildHeader(ObsPresence.@Nullable Report report) {
        this.shownReport = report;
        this.clearButtons();
        this.statusWidgets.forEach(this::removeWidget);
        this.statusWidgets.clear();

        int x = 10;
        for (Tab t : Tab.values()) {
            ButtonGeneric button = new ButtonGeneric(x, TABS_Y, -1, 20, StringUtils.translate(t.translationKey));
            button.setEnabled(tab != t);
            this.addButton(button, new TabListener(t, this));
            x += button.getWidth() + 2;
        }
        if (report == null) {
            return;
        }

        ObsPresence.Status status = report.status();
        String key = "layercast.gui.obs." + status.name().toLowerCase(Locale.ROOT);
        String text = statusColor(status) + StringUtils.translate(key, report.shownLayers());
        int textWidth = this.getStringWidth(text);
        this.statusWidgets.add(this.addLabel(12, STATUS_Y, textWidth, 20, 0xFFFFFFFF, text));
        this.statusWidgets.add(this.addWidget(new WidgetHoverInfo(12, STATUS_Y + 4, textWidth, 12, key + ".hover", this.channel())));

        ButtonGeneric download = new ButtonGeneric(12 + textWidth + 8, STATUS_Y, -1, 20,
            StringUtils.translate("layercast.gui.button.download_plugin"),
            "layercast.gui.button.download_plugin.hover");
        this.addButton(download, (button, mouseButton) -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(OBS_PLUGIN_URL)));
    }

    private String channel() {
        SharingService sharing = LayerCast.sharing();
        String channel = sharing != null ? sharing.channelName() : null;
        return channel != null ? channel : Configs.Generic.CHANNEL.getStringValue();
    }

    private static String statusColor(ObsPresence.Status status) {
        return switch (status) {
            case CONNECTED -> GuiBase.TXT_GREEN;
            case NOT_DETECTED -> GuiBase.TXT_GOLD;
            default -> GuiBase.TXT_GRAY;
        };
    }

    @Override
    protected int getBrowserHeight() {
        return super.getBrowserHeight() - (listY() - STATUS_Y);
    }

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS ? super.getConfigWidth() : 160;
    }

    @Override
    protected boolean useKeybindSearch() {
        return tab == Tab.HOTKEYS;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case GENERIC -> Configs.Generic.OPTIONS;
            case LAYERS -> Configs.Split.options();
            case HOTKEYS -> Configs.Hotkeys.HOTKEYS;
        };
        return ConfigOptionWrapper.createFor(configs);
    }

    private enum Tab {
        GENERIC("layercast.gui.button.config_gui.generic"),
        LAYERS("layercast.gui.button.config_gui.layers"),
        HOTKEYS("layercast.gui.button.config_gui.hotkeys");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private record TabListener(Tab target, GuiConfigs parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GuiConfigs.tab = this.target;
            this.parent.setListPosition(10, listY());
            this.parent.reCreateListWidget();
            if (this.parent.getListWidget() != null) {
                this.parent.getListWidget().resetScrollbarPosition();
            }
            this.parent.initGui();
        }
    }
}
