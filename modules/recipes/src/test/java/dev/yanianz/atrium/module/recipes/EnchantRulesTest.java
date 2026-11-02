package dev.yanianz.atrium.module.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnchantRulesTest {
    @Test
    void normalisesPlainAndNamespacedNames() {
        assertEquals("minecraft:sharpness", EnchantRules.normalize("Sharpness"));
        assertEquals("minecraft:sharpness", EnchantRules.normalize("  MINECRAFT:SHARPNESS "));
        assertEquals("custom:thing", EnchantRules.normalize("custom:thing"));
        assertEquals("", EnchantRules.normalize(null));
        assertEquals("", EnchantRules.normalize("  "));
    }

    @Test
    void capsLevelsAndRemovesDisabledEnchantments() {
        EnchantRules rules = EnchantRules.from(List.of("Mending"), Map.of("Sharpness", 3));
        assertTrue(rules.isActive());

        assertEquals(3, rules.allowedLevel("minecraft:sharpness", 5));
        assertEquals(2, rules.allowedLevel("minecraft:sharpness", 2));
        assertEquals(0, rules.allowedLevel("minecraft:mending", 1));
        assertEquals(5, rules.allowedLevel("minecraft:unbreaking", 5));
        assertTrue(rules.isDisabled("mending"));
        assertEquals(3, rules.cap("sharpness"));
        assertEquals(EnchantRules.NO_CAP, rules.cap("unbreaking"));
    }

    @Test
    void emptyRulesLeaveEverythingAlone() {
        EnchantRules rules = EnchantRules.empty();
        assertFalse(rules.isActive());
        assertEquals(7, rules.allowedLevel("minecraft:sharpness", 7));
        assertEquals(Map.of("minecraft:sharpness", 7),
                rules.sanitize(Map.of("minecraft:sharpness", 7)));
    }

    @Test
    void sanitizeFiltersAndCapsWhileKeepingOrder() {
        EnchantRules rules = EnchantRules.from(List.of("frost_walker"), Map.of("protection", 2));
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("minecraft:sharpness", 5);
        input.put("minecraft:protection", 4);
        input.put("minecraft:frost_walker", 2);
        input.put("minecraft:unbreaking", 3);

        Map<String, Integer> sanitized = rules.sanitize(input);
        assertEquals(List.of("minecraft:sharpness", "minecraft:protection", "minecraft:unbreaking"),
                List.copyOf(sanitized.keySet()));
        assertEquals(5, sanitized.get("minecraft:sharpness"));
        assertEquals(2, sanitized.get("minecraft:protection"));
        assertEquals(3, sanitized.get("minecraft:unbreaking"));
    }

    @Test
    void ignoresBlankNamesAndNonPositiveCaps() {
        EnchantRules rules = EnchantRules.from(List.of("", "  ", "mending"), Map.of("sharpness", 0, "luck", -2));
        assertTrue(rules.isDisabled("mending"));
        assertFalse(rules.maxLevels().containsKey("minecraft:sharpness"));
        assertFalse(rules.maxLevels().containsKey("minecraft:luck"));
    }

    @Test
    void clampsCapsToTheEnchantmentMaximum() {
        EnchantRules rules = EnchantRules.from(List.of(), Map.of("sharpness", 10_000));
        assertEquals(255, rules.cap("sharpness"));
    }
}
