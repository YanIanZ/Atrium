package dev.yanianz.atrium.module.worth;

import java.util.ArrayList;
import java.util.List;

/**
 * What a price engine can reproduce. Reported by {@code /worth engine}; an engine that cannot price a
 * distinction Atrium's own table handles simply refuses the item instead of guessing a price.
 */
record PriceEngineCapabilities(boolean sellPrices, boolean perPlayerPrices, boolean customModelData,
        boolean customItems, boolean enchantmentValues, boolean durability, boolean livePrices, boolean fileImport) {

    /** Capabilities with a stable key used by the lang files. */
    enum Feature {
        SELL_PRICES("sell-prices"),
        PER_PLAYER_PRICES("per-player-prices"),
        CUSTOM_MODEL_DATA("custom-model-data"),
        CUSTOM_ITEMS("custom-items"),
        ENCHANTMENT_VALUES("enchantment-values"),
        DURABILITY("durability"),
        LIVE_PRICES("live-prices"),
        FILE_IMPORT("file-import");

        final String key;

        Feature(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /** Atrium's own table: exact item matching, durability and enchantment rules, imported from files. */
    static PriceEngineCapabilities atrium() {
        return new PriceEngineCapabilities(true, false, true, false, true, true, false, true);
    }

    /**
     * EconomyShopGUI: per-player multipliers, its own item matching (including premium custom items) and
     * live prices from the loaded shops. Only its Vault sell price can be paid through Atrium's economy.
     */
    static PriceEngineCapabilities economyShopGui() {
        return new PriceEngineCapabilities(true, true, true, true, true, false, true, true);
    }

    /** ShopGUIPlus: per-player price modifiers and its own item matching, live prices from loaded shops. */
    static PriceEngineCapabilities shopGuiPlus() {
        return new PriceEngineCapabilities(true, true, true, true, true, false, true, true);
    }

    static PriceEngineCapabilities of(PriceEngineKind kind) {
        return switch (kind) {
            case AUTO, ATRIUM -> atrium();
            case ECONOMYSHOPGUI -> economyShopGui();
            case SHOPGUIPLUS -> shopGuiPlus();
        };
    }

    boolean supports(Feature feature) {
        return switch (feature) {
            case SELL_PRICES -> sellPrices;
            case PER_PLAYER_PRICES -> perPlayerPrices;
            case CUSTOM_MODEL_DATA -> customModelData;
            case CUSTOM_ITEMS -> customItems;
            case ENCHANTMENT_VALUES -> enchantmentValues;
            case DURABILITY -> durability;
            case LIVE_PRICES -> livePrices;
            case FILE_IMPORT -> fileImport;
        };
    }

    List<Feature> supported() {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (supports(feature)) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    List<Feature> missing() {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (!supports(feature)) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    /** Comma-separated stable keys, for /worth engine and logs. */
    static String describe(List<Feature> features) {
        return features.stream().map(Feature::key).reduce((a, b) -> a + ", " + b).orElse("none");
    }

    @Override
    public String toString() {
        return "supported=" + describe(supported()) + "; missing=" + describe(missing());
    }
}
