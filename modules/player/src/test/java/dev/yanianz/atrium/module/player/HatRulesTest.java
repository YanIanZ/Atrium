package dev.yanianz.atrium.module.player;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HatRulesTest {
    @Test void filtersArmourEnchantmentsAndFilledShulkers() {
        var filters = HatRules.Filters.from(List.of(), true, true, true);
        assertEquals(HatRules.Result.ARMOUR, HatRules.check(filters, "minecraft:diamond_helmet", true, false, false));
        assertEquals(HatRules.Result.ENCHANTED, HatRules.check(filters, "minecraft:pumpkin", false, true, false));
        assertEquals(HatRules.Result.FILLED_SHULKER,
                HatRules.check(filters, "minecraft:shulker_box", false, false, true));
        assertEquals(HatRules.Result.ALLOW, HatRules.check(filters, "minecraft:pumpkin", false, false, false));
    }

    @Test void whitelistReplacesTheOtherFilters() {
        var filters = HatRules.Filters.from(List.of("minecraft:pumpkin"), true, true, true);
        assertEquals(Set.of("minecraft:pumpkin"), filters.whitelist());
        assertEquals(HatRules.Result.ALLOW,
                HatRules.check(filters, "minecraft:pumpkin", true, true, true));
        assertEquals(HatRules.Result.NOT_WHITELISTED,
                HatRules.check(filters, "minecraft:diamond_helmet", false, false, false));
    }

    @Test void blankWhitelistEntriesAreIgnoredAndEmptyFiltersAllowEverything() {
        var filters = HatRules.Filters.from(List.of("  ", ""), false, false, false);
        assertTrue(filters.whitelist().isEmpty());
        assertEquals(HatRules.Result.ALLOW, HatRules.check(filters, "minecraft:pumpkin", true, true, true));
        assertEquals(HatRules.Result.ALLOW,
                HatRules.check(null, "minecraft:pumpkin", true, true, true));
    }
}
