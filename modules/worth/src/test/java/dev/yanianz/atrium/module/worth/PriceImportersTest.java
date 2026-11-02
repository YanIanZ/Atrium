package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceImportersTest {
    private static ImportParse parse(PriceImportSource source, String name, String text) {
        return source.importer().parse(Map.of(name, text));
    }

    @Test
    void economyShopGuiShopPagesAreParsed() {
        String shops = """
                pages:
                  page1:
                    gui-rows: 6
                    items:
                      '1':
                        material: PAPER
                        name: "&aEgyptian Papyrus"
                        buy: 6
                        sell: 2
                      '2':
                        material: "ia:custom_sword"
                        sell: 10
                      '3':
                        material: DIAMOND
                        sell: -1
                      '4':
                        material: BOOK
                        sell: 3
                        enchantments:
                          - EFFICIENCY:1
                      '5':
                        material: EMERALD
                        sell: 5
                        lore:
                          - "shiny"
                """;
        ImportParse parsed = parse(PriceImportSource.ECONOMYSHOPGUI, "shops/Ores.yml", shops);
        assertEquals(1, parsed.candidates().size());
        ImportCandidate paper = parsed.candidates().getFirst();
        assertEquals("paper", paper.material());
        assertEquals(0, new BigDecimal("2").compareTo(paper.unitPrice()));
        assertEquals("Egyptian Papyrus", paper.name());
        assertEquals(1, paper.amount());
        assertTrue(parsed.skipped().stream().anyMatch(s -> s.reason().equals("custom-item")));
        assertTrue(parsed.skipped().stream().anyMatch(s -> s.reason().equals("not-sellable")));
        assertEquals(2, parsed.skipped().stream().filter(s -> s.reason().equals("item-meta")).count());
    }

    @Test
    void shopGuiPlusItemsAreDivisibleByTheirQuantity() {
        String shops = """
                shops:
                  blocks:
                    name: "&e&lBlocks"
                    items:
                      '1':
                        type: item
                        item:
                          material: BREAD
                          quantity: 32
                          name: "&aYummy bread"
                        buyPrice: 75.5
                        sellPrice: 25
                        slot: 0
                      '2':
                        type: command
                        item:
                          material: PAPER
                        commands:
                          - "say hi"
                        sellPrice: 5
                      '3':
                        type: item
                        item:
                          material: STONE_PICKAXE
                          quantity: 1
                          enchantments:
                            - EFFICIENCY: 1
                        sellPrice: 25
                      '4':
                        type: item
                        item:
                          material: WOOL
                          damage: 1
                        sellPrice: 4
                """;
        ImportParse parsed = parse(PriceImportSource.SHOPGUIPLUS, "shops/blocks.yml", shops);
        assertEquals(1, parsed.candidates().size());
        ImportCandidate bread = parsed.candidates().getFirst();
        assertEquals("bread", bread.material());
        assertEquals(32, bread.amount());
        assertEquals(0, new BigDecimal("25").compareTo(bread.totalPrice()));
        assertEquals(0, new BigDecimal("0.78125").compareTo(bread.unitPrice()));
        assertEquals("Yummy bread", bread.name());
        assertTrue(parsed.skipped().stream().anyMatch(s -> s.reason().equals("item-meta")));
    }

    @Test
    void shopGuiPlusFlatItemFormatIsParsed() {
        String shops = """
                items:
                  diamond:
                    material: DIAMOND
                    buyPrice: 50.0
                    sellPrice: 25.0
                  stone:
                    material: STONE
                    buyPrice: 5.0
                    sellPrice: 2.5
                """;
        ImportParse parsed = parse(PriceImportSource.SHOPGUIPLUS, "shops/blocks.yml", shops);
        assertEquals(2, parsed.candidates().size());
        assertEquals("diamond", parsed.candidates().getFirst().material());
        assertEquals(0, new BigDecimal("25.0").compareTo(parsed.candidates().getFirst().unitPrice()));
    }

    @Test
    void essentialsWorthIsParsedAndLegacyDataIsReported() {
        String worth = """
                worth:
                  # legacy names without underscores
                  diamondpickaxe: 650.0
                  diamond: 200
                  wool:
                    '0': 20
                    '*': 2.5
                  stonebutton: -1
                """;
        ImportParse parsed = parse(PriceImportSource.ESSENTIALS, "Essentials/worth.yml", worth);
        assertEquals(2, parsed.candidates().size());
        assertEquals("diamondpickaxe", parsed.candidates().get(0).material());
        assertEquals(0, new BigDecimal("650.0").compareTo(parsed.candidates().get(0).unitPrice()));
        assertEquals("diamond", parsed.candidates().get(1).material());
        assertTrue(parsed.skipped().stream().anyMatch(s -> s.reason().equals("legacy-data")));
        assertTrue(parsed.skipped().stream().anyMatch(s -> s.reason().equals("not-sellable")));
    }

    @Test
    void cmiWorthIsParsedAndVariantsAreReported() {
        String worth = """
                worth:
                  ACACIA_BOAT: '1.0'
                  DIAMOND: '2.0'
                  ZOMBIE_SPAWN_EGG>ZOMBIE: '0.01'
                """;
        ImportParse parsed = parse(PriceImportSource.CMI, "CMI/Saves/Worth.yml", worth);
        assertEquals(2, parsed.candidates().size());
        assertEquals(List.of("acacia_boat", "diamond"),
                parsed.candidates().stream().map(ImportCandidate::material).toList());
        assertEquals(0, new BigDecimal("2.0").compareTo(parsed.candidates().get(1).unitPrice()));
        assertEquals(1, parsed.skipped().size());
        assertEquals("entity-variant", parsed.skipped().getFirst().reason());
    }

    @Test
    void brokenYamlIsReportedInsteadOfThrowing() {
        ImportParse parsed = parse(PriceImportSource.CMI, "CMI/Saves/Worth.yml", "worth: [unclosed");
        assertTrue(parsed.candidates().isEmpty());
        assertEquals(1, parsed.skipped().size());
        assertEquals("invalid-yaml", parsed.skipped().getFirst().reason());
    }

    @Test
    void aMissingWorthSectionIsReported() {
        ImportParse parsed = parse(PriceImportSource.ESSENTIALS, "Essentials/worth.yml", "other: true");
        assertTrue(parsed.candidates().isEmpty());
        assertEquals("no-worth-section", parsed.skipped().getFirst().reason());
    }

    @Test
    void legacyMaterialNamesResolveOnlyWhenUnique() {
        Map<String, List<String>> index = MaterialNames.index(
                List.of("DIAMOND_PICKAXE", "DIAMOND", "COBBLESTONE", "OAK_LOG"));
        assertEquals("diamond_pickaxe", MaterialNames.unique(index, "diamondpickaxe").orElseThrow());
        assertEquals("diamond", MaterialNames.unique(index, "DIAMOND").orElseThrow());
        assertTrue(MaterialNames.unique(index, "oaklog").isPresent());

        Map<String, List<String>> ambiguous = MaterialNames.index(List.of("GRASS", "G_R_A_S_S"));
        assertTrue(MaterialNames.unique(ambiguous, "grass").isEmpty());
    }

    @Test
    void displayNamesAreImportedAsPlainText() {
        assertEquals("Ruby Sword", ImportText.cleanName("&cRuby &lSword"));
        assertEquals("Ruby Sword", ImportText.cleanName("\u00a7cRuby \u00a7lSword"));
        assertEquals("Ruby", ImportText.cleanName("&#ff0000Ruby"));
        assertNull(ImportText.cleanName("&c"));
        assertNull(ImportText.cleanName("   "));
    }
}
