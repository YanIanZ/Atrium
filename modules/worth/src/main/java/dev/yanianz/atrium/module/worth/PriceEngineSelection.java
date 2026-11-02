package dev.yanianz.atrium.module.worth;

import java.util.Optional;

/**
 * Pure engine choice. Kept separate from Bukkit so the fallback order can be tested without a server.
 *
 * <p>{@code auto} prefers EconomyShopGUI, then ShopGUIPlus, then Atrium's own price table. An explicitly
 * configured external engine that is unavailable falls back to atrium with a reason the caller logs once.
 */
final class PriceEngineSelection {
    record Selection(PriceEngineKind engine, boolean fallback, String reason) {
        static Selection direct(PriceEngineKind engine) {
            return new Selection(engine, false, "");
        }

        static Selection fallback(String reason) {
            return new Selection(PriceEngineKind.ATRIUM, true, reason);
        }
    }

    static final String PLUGIN_MISSING = "plugin-missing";
    static final String PLUGIN_DISABLED = "plugin-disabled";
    static final String START_FAILED = "start-failed";
    static final String LOOKUP_FAILED = "lookup-failed";

    private PriceEngineSelection() {}

    /** The engine backed by a plugin, by its plugin name; empty for any other plugin. */
    static Optional<PriceEngineKind> kindOfPlugin(String pluginName) {
        if ("EconomyShopGUI".equals(pluginName) || "EconomyShopGUI-Premium".equals(pluginName)) {
            return Optional.of(PriceEngineKind.ECONOMYSHOPGUI);
        }
        if ("ShopGUIPlus".equals(pluginName)) {
            return Optional.of(PriceEngineKind.SHOPGUIPLUS);
        }
        return Optional.empty();
    }

    static Selection select(PriceEngineKind requested, boolean economyShopGuiAvailable, boolean shopGuiPlusAvailable) {
        PriceEngineKind wanted = requested == null ? PriceEngineKind.AUTO : requested;
        return switch (wanted) {
            case ATRIUM -> Selection.direct(PriceEngineKind.ATRIUM);
            case AUTO -> {
                if (economyShopGuiAvailable) {
                    yield Selection.direct(PriceEngineKind.ECONOMYSHOPGUI);
                }
                if (shopGuiPlusAvailable) {
                    yield Selection.direct(PriceEngineKind.SHOPGUIPLUS);
                }
                yield Selection.direct(PriceEngineKind.ATRIUM);
            }
            case ECONOMYSHOPGUI -> economyShopGuiAvailable
                    ? Selection.direct(PriceEngineKind.ECONOMYSHOPGUI)
                    : Selection.fallback(PLUGIN_MISSING);
            case SHOPGUIPLUS -> shopGuiPlusAvailable
                    ? Selection.direct(PriceEngineKind.SHOPGUIPLUS)
                    : Selection.fallback(PLUGIN_MISSING);
        };
    }

    /** A plugin was disabled: true when it backs the active engine, which must then fall back to atrium. */
    static boolean fallBackOnDisable(PriceEngineKind active, String disabledPlugin) {
        return active != PriceEngineKind.ATRIUM && kindOfPlugin(disabledPlugin).orElse(null) == active;
    }

    /**
     * A plugin was enabled: true when Atrium priced with atrium only because that plugin was missing or
     * disabled and the configuration now selects it.
     */
    static boolean returnOnEnable(PriceEngineKind active, PriceEngineKind configured, String fallbackReason,
            String enabledPlugin, PriceEngineKind selected) {
        PriceEngineKind enabled = kindOfPlugin(enabledPlugin).orElse(null);
        if (enabled == null || active != PriceEngineKind.ATRIUM || selected != enabled) {
            return false;
        }
        return configured == enabled || PLUGIN_DISABLED.equals(fallbackReason);
    }
}
