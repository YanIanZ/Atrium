package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PriceEngineSelectionTest {
    @Test
    void autoPrefersEconomyShopGuiThenShopGuiPlusThenAtrium() {
        assertEquals(PriceEngineKind.ECONOMYSHOPGUI,
                PriceEngineSelection.select(PriceEngineKind.AUTO, true, true).engine());
        assertEquals(PriceEngineKind.SHOPGUIPLUS,
                PriceEngineSelection.select(PriceEngineKind.AUTO, false, true).engine());
        assertEquals(PriceEngineKind.ATRIUM,
                PriceEngineSelection.select(PriceEngineKind.AUTO, false, false).engine());
    }

    @Test
    void explicitMissingEngineFallsBackWithAReason() {
        PriceEngineSelection.Selection selection =
                PriceEngineSelection.select(PriceEngineKind.ECONOMYSHOPGUI, false, true);
        assertEquals(PriceEngineKind.ATRIUM, selection.engine());
        assertTrue(selection.fallback());
        assertEquals(PriceEngineSelection.PLUGIN_MISSING, selection.reason());

        PriceEngineSelection.Selection shopGui =
                PriceEngineSelection.select(PriceEngineKind.SHOPGUIPLUS, true, false);
        assertEquals(PriceEngineKind.ATRIUM, shopGui.engine());
        assertEquals(PriceEngineSelection.PLUGIN_MISSING, shopGui.reason());

        PriceEngineSelection.Selection direct =
                PriceEngineSelection.select(PriceEngineKind.ATRIUM, true, true);
        assertEquals(PriceEngineKind.ATRIUM, direct.engine());
        assertFalse(direct.fallback());
    }

    @Test
    void pluginNamesMapToEngines() {
        assertEquals(Optional.of(PriceEngineKind.ECONOMYSHOPGUI),
                PriceEngineSelection.kindOfPlugin("EconomyShopGUI"));
        assertEquals(Optional.of(PriceEngineKind.ECONOMYSHOPGUI),
                PriceEngineSelection.kindOfPlugin("EconomyShopGUI-Premium"));
        assertEquals(Optional.of(PriceEngineKind.SHOPGUIPLUS),
                PriceEngineSelection.kindOfPlugin("ShopGUIPlus"));
        assertEquals(Optional.empty(), PriceEngineSelection.kindOfPlugin("Vault"));
    }

    @Test
    void fallingBackAndReturningOnlyHappensForTheActiveEngine() {
        assertTrue(PriceEngineSelection.fallBackOnDisable(PriceEngineKind.SHOPGUIPLUS, "ShopGUIPlus"));
        assertFalse(PriceEngineSelection.fallBackOnDisable(PriceEngineKind.SHOPGUIPLUS, "EconomyShopGUI"));
        assertFalse(PriceEngineSelection.fallBackOnDisable(PriceEngineKind.ATRIUM, "ShopGUIPlus"));

        assertTrue(PriceEngineSelection.returnOnEnable(PriceEngineKind.ATRIUM, PriceEngineKind.ECONOMYSHOPGUI, "",
                "EconomyShopGUI", PriceEngineKind.ECONOMYSHOPGUI));
        assertTrue(PriceEngineSelection.returnOnEnable(PriceEngineKind.ATRIUM, PriceEngineKind.AUTO,
                PriceEngineSelection.PLUGIN_DISABLED, "ShopGUIPlus", PriceEngineKind.SHOPGUIPLUS));
        assertFalse(PriceEngineSelection.returnOnEnable(PriceEngineKind.ATRIUM, PriceEngineKind.AUTO, "",
                "ShopGUIPlus", PriceEngineKind.ECONOMYSHOPGUI));
    }

    @Test
    void kindsParseWithAliases() {
        assertEquals(Optional.of(PriceEngineKind.ECONOMYSHOPGUI), PriceEngineKind.parse("ESG"));
        assertEquals(Optional.of(PriceEngineKind.SHOPGUIPLUS), PriceEngineKind.parse("shopgui"));
        assertEquals(Optional.of(PriceEngineKind.ATRIUM), PriceEngineKind.parse("internal"));
        assertEquals(Optional.of(PriceEngineKind.AUTO), PriceEngineKind.parse("AUTO"));
        assertEquals(Optional.empty(), PriceEngineKind.parse("nonsense"));
    }

    @Test
    void capabilityMatrixNamesTheDifferences() {
        PriceEngineCapabilities atrium = PriceEngineCapabilities.atrium();
        assertTrue(atrium.supports(PriceEngineCapabilities.Feature.SELL_PRICES));
        assertTrue(atrium.supports(PriceEngineCapabilities.Feature.ENCHANTMENT_VALUES));
        assertFalse(atrium.supports(PriceEngineCapabilities.Feature.PER_PLAYER_PRICES));
        assertFalse(atrium.supports(PriceEngineCapabilities.Feature.LIVE_PRICES));

        PriceEngineCapabilities shop = PriceEngineCapabilities.shopGuiPlus();
        assertTrue(shop.supports(PriceEngineCapabilities.Feature.PER_PLAYER_PRICES));
        assertTrue(shop.supports(PriceEngineCapabilities.Feature.CUSTOM_ITEMS));
        assertFalse(shop.supports(PriceEngineCapabilities.Feature.DURABILITY));
        assertTrue(PriceEngineCapabilities.describe(shop.missing()).contains("durability"));
        assertTrue(PriceEngineCapabilities.describe(shop.supported()).contains("sell-prices"));
    }
}
