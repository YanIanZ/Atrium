package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.module.worth.PriceEntry.Attribute;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PriceTableTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static PriceTable table(PriceTable.Options options, PriceEntry... entries) {
        return new PriceTable(List.of(entries), Map.of("sharpness", d("5"), "minecraft:unbreaking", d("2")), options);
    }

    private static PriceTable table(PriceEntry... entries) {
        return table(PriceTable.Options.defaults(), entries);
    }

    private static Optional<BigDecimal> price(PriceTable t, ItemFacts f) {
        return t.unitPrice(f);
    }

    @Test
    void plainEntryMatchesOnlyVanillaItem() {
        PriceTable t = table(PriceEntry.plain("diamond", d("100")));
        ItemFacts vanilla = ItemFacts.plain("diamond");
        assertEquals(Optional.of(d("100.00")), price(t, vanilla));
        // A vanilla price must never pay for custom variants.
        assertTrue(price(t, vanilla.withCustomModelData(List.of("1001"))).isEmpty());
        assertTrue(price(t, vanilla.withItemModel("myplugin:ruby")).isEmpty());
        assertTrue(price(t, vanilla.withName("Shiny")).isEmpty());
        assertTrue(price(t, vanilla.withLore(List.of("Quest item"))).isEmpty());
        assertTrue(price(t, vanilla.withEnchantments(Map.of("sharpness", 1))).isEmpty());
        assertTrue(price(t, vanilla.withOtherComponents(Set.of("minecraft:custom_data"))).isEmpty());
        assertTrue(price(t, ItemFacts.plain("emerald")).isEmpty());
    }

    @Test
    void customModelDataMustMatchExactly() {
        PriceEntry ruby = new PriceEntry("ruby", "emerald", d("250"), null, List.of("1001"), null, null, Set.of());
        PriceTable t = table(PriceEntry.plain("emerald", d("25")), ruby);
        ItemFacts emerald = ItemFacts.plain("emerald");
        assertEquals(Optional.of(d("25.00")), price(t, emerald));
        assertEquals(Optional.of(d("250.00")), price(t, emerald.withCustomModelData(List.of("1001"))));
        assertTrue(price(t, emerald.withCustomModelData(List.of("1002"))).isEmpty());
        assertTrue(price(t, emerald.withCustomModelData(List.of("1001", "s:x"))).isEmpty());
        // Specified CMD but an extra custom name -> no match.
        assertTrue(price(t, emerald.withCustomModelData(List.of("1001")).withName("Ruby")).isEmpty());
    }

    @Test
    void customNameAndItemModelMustMatchExactly() {
        PriceEntry named = new PriceEntry("relic", "gold_ingot", d("500"), "atrium:relic", null, "Ancient Relic", null, Set.of());
        PriceTable t = table(named);
        ItemFacts base = ItemFacts.plain("gold_ingot");
        assertEquals(Optional.of(d("500.00")), price(t, base.withItemModel("atrium:relic").withName("Ancient Relic")));
        assertTrue(price(t, base.withItemModel("atrium:relic").withName("ancient relic")).isEmpty());
        assertTrue(price(t, base.withItemModel("atrium:relic")).isEmpty());
        assertTrue(price(t, base.withName("Ancient Relic")).isEmpty());
        assertTrue(price(t, base).isEmpty());
    }

    @Test
    void enchantmentsMustMatchExactlyOrBeAccepted() {
        PriceEntry exact = new PriceEntry("sharp5", "diamond_sword", d("400"), null, null, null, Map.of("sharpness", 5), Set.of());
        PriceEntry any = new PriceEntry("ench", "diamond_sword", d("150"), null, null, null, null, Set.of(Attribute.ENCHANTMENTS));
        PriceTable onlyExact = table(PriceEntry.plain("diamond_sword", d("150")), exact);
        ItemFacts sword = ItemFacts.plain("diamond_sword");
        assertEquals(Optional.of(d("400.00")), price(onlyExact, sword.withEnchantments(Map.of("sharpness", 5))));
        assertTrue(price(onlyExact, sword.withEnchantments(Map.of("sharpness", 4))).isEmpty());
        assertTrue(price(onlyExact, sword.withEnchantments(Map.of("sharpness", 5, "unbreaking", 1))).isEmpty());

        PriceTable both = table(exact, any);
        // Exact entry is more specific and wins; no enchantment bonus for an exact entry.
        assertEquals(Optional.of(d("400.00")), price(both, sword.withEnchantments(Map.of("sharpness", 5))));
        // Accepting entry adds enchantment-values per level: 150 + 3*5 + 2*2.
        assertEquals(Optional.of(d("169.00")), price(both, sword.withEnchantments(Map.of("sharpness", 3, "unbreaking", 2))));
        // Accepting enchantments does not accept a custom name.
        assertTrue(price(both, sword.withEnchantments(Map.of("sharpness", 3)).withName("Excalibur")).isEmpty());
    }

    @Test
    void acceptListAllowsAnyValue() {
        PriceEntry loreOk = new PriceEntry("lored", "diamond", d("90"), null, null, null, null, Set.of(Attribute.LORE));
        PriceTable t = table(loreOk);
        assertEquals(Optional.of(d("90.00")), price(t, ItemFacts.plain("diamond").withLore(List.of("Mined by Steve"))));
        assertEquals(Optional.of(d("90.00")), price(t, ItemFacts.plain("diamond")));
    }

    @Test
    void unsellableLoreAndNameBlocking() {
        PriceEntry anyName = new PriceEntry("named", "diamond", d("100"), null, null, null, null, Set.of(Attribute.NAME, Attribute.LORE));
        PriceTable.Options options = new PriceTable.Options(true, false, true, List.of("Soulbound"), PriceTable.ContainerMode.EMPTY_ONLY, 2);
        PriceTable t = table(options, anyName);
        assertEquals(Optional.of(d("100.00")), price(t, ItemFacts.plain("diamond")));
        assertTrue(price(t, ItemFacts.plain("diamond").withName("Gem")).isEmpty(), "custom names blocked");
        assertTrue(price(t, ItemFacts.plain("diamond").withLore(List.of("§7SOULBOUND item"))).isEmpty());
    }

    @Test
    void durabilityDevaluationAndRequirement() {
        PriceTable t = table(PriceEntry.plain("diamond_pickaxe", d("200")));
        ItemFacts damaged = ItemFacts.plain("diamond_pickaxe").withDamage(390, 1561);
        assertEquals(Optional.of(d("150.03")), price(t, damaged)); // 200 * 1171 / 1561 = 150.0320..., rounded down
        PriceTable strict = table(new PriceTable.Options(true, true, false, List.of(), PriceTable.ContainerMode.EMPTY_ONLY, 2),
                PriceEntry.plain("diamond_pickaxe", d("200")));
        assertTrue(price(strict, damaged).isEmpty());
        assertEquals(Optional.of(d("200.00")), price(strict, ItemFacts.plain("diamond_pickaxe").withDamage(0, 1561)));
        PriceTable broken = table(PriceEntry.plain("diamond_pickaxe", d("200")));
        assertTrue(price(broken, ItemFacts.plain("diamond_pickaxe").withDamage(1561, 1561)).isEmpty(), "zero value is unsellable");
    }

    @Test
    void zeroPriceIsUnsellable() {
        assertTrue(price(table(PriceEntry.plain("dirt", d("0"))), ItemFacts.plain("dirt")).isEmpty());
    }

    @Test
    void containers() {
        ItemFacts full = ItemFacts.plain("shulker_box")
                .withContents(List.of(new ItemFacts.Content(ItemFacts.plain("diamond"), 10)));
        PriceEntry box = PriceEntry.plain("shulker_box", d("50"));
        PriceEntry diamond = PriceEntry.plain("diamond", d("100"));
        PriceTable emptyOnly = table(box, diamond);
        assertTrue(price(emptyOnly, full).isEmpty());
        assertEquals(Optional.of(d("50.00")), price(emptyOnly, ItemFacts.plain("shulker_box")));

        PriceTable.Options sum = new PriceTable.Options(true, false, false, List.of(), PriceTable.ContainerMode.SUM_CONTENTS, 2);
        assertEquals(Optional.of(d("1050.00")), price(table(sum, box, diamond), full));
        ItemFacts mixed = full.withContents(List.of(new ItemFacts.Content(ItemFacts.plain("diamond"), 10),
                new ItemFacts.Content(ItemFacts.plain("diamond").withName("Quest"), 1)));
        assertTrue(price(table(sum, box, diamond), mixed).isEmpty(), "one unsellable item blocks the whole box");
    }

    @Test
    void cmdTokensAreCanonical() {
        assertEquals("1001", ItemFacts.cmdToken(1001f));
        assertEquals("1.5", ItemFacts.cmdToken(1.5f));
        assertEquals("1001", ItemFacts.normaliseToken("1001.0"));
        assertEquals("s:ruby", ItemFacts.normaliseToken("s:ruby"));
        assertEquals("s:ruby", ItemFacts.normaliseToken("ruby"));
    }
}
