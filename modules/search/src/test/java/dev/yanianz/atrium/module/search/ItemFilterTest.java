package dev.yanianz.atrium.module.search;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ItemFilterTest {
    private static ItemFilter.ItemKey key(Material type, String name, Integer cmd) {
        return new ItemFilter.ItemKey(type, name, cmd);
    }

    @Test void materialOnlyMatchesEveryVariantOfThatMaterial() {
        ItemFilter filter = new ItemFilter(Material.DIAMOND, null, null);
        assertTrue(filter.matches(key(Material.DIAMOND, null, null)));
        assertTrue(filter.matches(key(Material.DIAMOND, "Blue Diamond", 7)));
        assertFalse(filter.matches(key(Material.DIRT, null, null)));
        assertFalse(filter.matches(null));
    }

    @Test void nameFilterIsAnAdditionalConditionNotReplacedByTheMaterial() {
        // "Material only" is not enough: a diamond named "Rock" must not match here.
        ItemFilter filter = new ItemFilter(Material.DIAMOND, "blue", null);
        assertTrue(filter.matches(key(Material.DIAMOND, "A Blue DIAMOND", null)));
        assertFalse(filter.matches(key(Material.DIAMOND, "Rock", null)));
        assertFalse(filter.matches(key(Material.DIAMOND, null, null)), "an unnamed item cannot match a name filter");
        assertFalse(filter.matches(key(Material.DIRT, "Blue Diamond", null)));
    }

    @Test void nameComparisonIgnoresCaseAndWhitespace() {
        ItemFilter filter = new ItemFilter(Material.DIAMOND, "Red  Sword", null);
        assertTrue(filter.matches(key(Material.DIAMOND, "red sword", null)));
        assertTrue(filter.matches(key(Material.DIAMOND, "  REDSWORD  ", null)));
        assertTrue(new ItemFilter(Material.DIAMOND, null, null).matches(key(Material.DIAMOND, "redsword", 3)));
    }

    @Test void customModelDataMustMatchExactly() {
        ItemFilter filter = new ItemFilter(Material.DIAMOND, null, 42);
        assertTrue(filter.matches(key(Material.DIAMOND, null, 42)));
        assertFalse(filter.matches(key(Material.DIAMOND, null, 41)));
        assertFalse(filter.matches(key(Material.DIAMOND, null, null)));
        assertTrue(new ItemFilter(Material.DIAMOND, null, null).matches(key(Material.DIAMOND, null, null)));
    }

    @Test void allFiltersAreCombinedWithAnd() {
        ItemFilter filter = new ItemFilter(Material.DIAMOND, "blue", 7);
        assertTrue(filter.matches(key(Material.DIAMOND, "Blue Gem", 7)));
        assertFalse(filter.matches(key(Material.DIAMOND, "Blue Gem", 8)));
        assertFalse(filter.matches(key(Material.DIAMOND, "Red Gem", 7)));
        assertFalse(filter.matches(key(Material.EMERALD, "Blue Gem", 7)));
    }

    @Test void parsesOptionsInAnyOrder() {
        ItemFilter.Options opts = ItemFilter.parseOptions("cmd:5 name:blue sword player:Steve");
        assertEquals("blue sword", opts.name());
        assertEquals(5, opts.customModelData());
        assertEquals("Steve", opts.player());

        opts = ItemFilter.parseOptions("player:Alex name:x cmd:0");
        assertEquals("x", opts.name());
        assertEquals(0, opts.customModelData());
        assertEquals("Alex", opts.player());
    }

    @Test void parsesQuotedNamesAndEmptyOptions() {
        assertEquals("red sword", ItemFilter.parseOptions("name:\"red sword\"").name());
        assertEquals("red sword", ItemFilter.parseOptions("name:'red sword'").name());
        assertEquals(ItemFilter.Options.NONE, ItemFilter.parseOptions(null));
        assertEquals(ItemFilter.Options.NONE, ItemFilter.parseOptions("   "));
    }

    @Test void rejectsUnknownMalformedOrEmptyOptions() {
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("diamond"));
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("junk name:x"));
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("name:"));
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("cmd:abc"));
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("cmd:-1"));
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("player:not a name"));
        assertThrows(IllegalArgumentException.class, () -> ItemFilter.parseOptions("lore:secret"));
    }

    @Test void describesTheActiveFilters() {
        assertEquals("diamond", new ItemFilter(Material.DIAMOND, null, null).describe());
        assertEquals("diamond name~\"blue\" cmd=7",
                new ItemFilter(Material.DIAMOND, "Blue", 7).describe());
    }
}
