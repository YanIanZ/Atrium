package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.ConfigService;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;

class WorthFileTest {
    private static CommentedConfigurationNode yaml(String text) throws Exception {
        return ConfigService.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesPlainAndDetailedEntries() throws Exception {
        var root = yaml("""
                items:
                  diamond: 100
                  cobblestone: 0.1
                  ruby:
                    material: emerald
                    price: 250
                    custom-model-data: [1001, 's:ruby']
                    name: Ruby
                    enchantments: {'minecraft:unbreaking': 1}
                    accept: [lore]
                  bad: -5
                  unknownthing: 3
                  weird:
                    material: diamond
                    price: 3
                    accept: [nonsense]
                enchantment-values:
                  minecraft:sharpness: 5
                """);
        WorthFile.Parsed parsed = WorthFile.parse(root, m -> !m.equals("unknownthing"));
        assertEquals(3, parsed.entries().size(), parsed.warnings().toString());
        assertEquals(3, parsed.warnings().size(), parsed.warnings().toString());
        PriceEntry diamond = parsed.entries().get(0);
        assertTrue(diamond.isPlain());
        assertEquals(0, new BigDecimal("100").compareTo(diamond.price()));
        assertEquals(new BigDecimal("0.1"), parsed.entries().get(1).price());
        PriceEntry ruby = parsed.entries().get(2);
        assertEquals("emerald", ruby.material());
        assertEquals(List.of("1001", "s:ruby"), ruby.customModelData());
        assertEquals("Ruby", ruby.name());
        assertEquals(Map.of("unbreaking", 1), ruby.enchantments());
        assertEquals(Set.of(PriceEntry.Attribute.LORE), ruby.accept());
        assertEquals(new BigDecimal("5"), parsed.enchantmentValues().get("sharpness"));
    }

    @Test
    void writeThenParseRoundTrips() throws Exception {
        var root = yaml("items:\n  dirt: 1\n");
        PriceEntry plain = PriceEntry.plain("diamond", new BigDecimal("2.5"));
        PriceEntry special = new PriceEntry("diamond-1", "diamond", new BigDecimal("10"), "a:b", List.of("7"), "Gem",
                Map.of("sharpness", 2), Set.of(PriceEntry.Attribute.COMPONENTS, PriceEntry.Attribute.LORE));
        WorthFile.write(root, plain);
        WorthFile.write(root, special);
        WorthFile.Parsed parsed = WorthFile.parse(root, m -> true);
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals(3, parsed.entries().size());
        assertEquals(plain, parsed.entries().get(1));
        PriceEntry back = parsed.entries().get(2);
        assertEquals(special.customModelData(), back.customModelData());
        assertEquals(special.accept(), back.accept());
        assertEquals(special.enchantments(), back.enchantments());
        assertEquals(0, special.price().compareTo(back.price()));
    }

    @Test
    void setWorthEntryForCustomItemIsExactAndReusesId() {
        ItemFacts custom = ItemFacts.plain("emerald").withCustomModelData(List.of("1001")).withLore(List.of("Shiny"));
        PriceEntry first = WorthCommands.entryFor(custom, new BigDecimal("5"), List.of(PriceEntry.plain("emerald", BigDecimal.ONE)));
        assertEquals("emerald-1", first.id());
        assertEquals(List.of("1001"), first.customModelData());
        assertTrue(first.matches(custom));
        assertFalse(first.matches(ItemFacts.plain("emerald")));
        PriceEntry again = WorthCommands.entryFor(custom, new BigDecimal("7"), List.of(first));
        assertEquals("emerald-1", again.id());
        PriceEntry plain = WorthCommands.entryFor(ItemFacts.plain("emerald"), BigDecimal.TEN, List.of(first));
        assertEquals("emerald", plain.id());
        assertTrue(plain.isPlain());
    }

    @Test
    void bundledDefaultsParse() throws Exception {
        try (InputStream in = WorthModule.class.getResourceAsStream("/worth/default-worth.yml")) {
            assertNotNull(in);
            WorthFile.Parsed parsed = WorthFile.parse(ConfigService.read(in), m -> true);
            assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
            PriceTable table = new PriceTable(parsed.entries(), parsed.enchantmentValues(), PriceTable.Options.defaults());
            assertEquals(Optional.of(new BigDecimal("100.00")), table.unitPrice(ItemFacts.plain("diamond")));
            assertEquals(Optional.of(new BigDecimal("165.00")),
                    table.unitPrice(ItemFacts.plain("diamond_sword").withEnchantments(Map.of("sharpness", 1))));
        }
    }

    @Test
    void storeLogsSalesAndClaimsReturnsOnce(@TempDir Path folder) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Database db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("worth-test"));
            try {
                db.migrate("worth", WorthStore.MIGRATIONS);
                WorthStore store = new WorthStore(db);
                UUID player = UUID.randomUUID();
                LocalDate today = LocalDate.of(2026, 11, 2);
                store.logSale(player, today, 1, "all", 3, new BigDecimal("10.25"), "diamond x3").join();
                store.logSale(player, today, 2, "hand", 1, new BigDecimal("0.75"), "dirt x1").join();
                store.logSale(player, today.minusDays(1), 3, "hand", 1, new BigDecimal("99"), "x").join();
                assertEquals(new BigDecimal("11.00"), store.soldOn(player, today).join());
                assertEquals(3, store.recentSales(player, 10).join().size());

                store.addReturn(player, new byte[] {1, 2, 3}).join();
                List<byte[]> claimed = store.claimReturns(player).join();
                assertEquals(1, claimed.size());
                assertArrayEquals(new byte[] {1, 2, 3}, claimed.getFirst());
                assertTrue(store.claimReturns(player).join().isEmpty(), "returns are delivered at most once");
            } finally {
                db.close();
            }
        }
    }
}
