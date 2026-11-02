package dev.yanianz.atrium.module.worth;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Price sources for the worth module. {@link #AUTO} is a configuration value only: it is resolved to one of
 * the concrete engines at startup (EconomyShopGUI, then ShopGUIPlus, then Atrium's own price table) and is
 * never the active engine.
 */
enum PriceEngineKind {
    AUTO("auto"),
    ATRIUM("atrium"),
    ECONOMYSHOPGUI("economyshopgui"),
    SHOPGUIPLUS("shopguiplus");

    final String id;

    PriceEngineKind(String id) {
        this.id = id;
    }

    boolean concrete() {
        return this != AUTO;
    }

    /** Parses a config or command value, including the common short aliases. */
    static Optional<PriceEngineKind> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        String compact = wanted.replace("_", "");
        for (PriceEngineKind kind : values()) {
            if (kind.id.equals(wanted) || kind.id.equals(compact)
                    || kind.name().toLowerCase(Locale.ROOT).equals(compact)) {
                return Optional.of(kind);
            }
        }
        return switch (wanted) {
            case "esg", "esgui", "economy_shop_gui" -> Optional.of(ECONOMYSHOPGUI);
            case "sgp", "shopgui", "shop_gui_plus" -> Optional.of(SHOPGUIPLUS);
            case "internal", "prices", "worth" -> Optional.of(ATRIUM);
            default -> Optional.empty();
        };
    }

    /** Values accepted by {@code /worth engine}, for tab completion. */
    static List<String> names() {
        return Arrays.stream(values()).map(k -> k.id).toList();
    }
}
