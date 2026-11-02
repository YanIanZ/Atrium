package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportPlannerTest {
    private static final PriceMaterials MATERIALS =
            PriceMaterials.of(List.of("diamond", "cobblestone", "diamond_pickaxe", "emerald"));

    private static ImportCandidate candidate(String material, BigDecimal price) {
        return new ImportCandidate(material + ".yml item", material, null, null, price, price, 1);
    }

    private static ImportCandidate named(String material, String name, BigDecimal price) {
        return new ImportCandidate(material + ".yml item", material, null, name, price, price, 1);
    }

    @Test
    void newPlainPricesUseTheMaterialAsId() {
        ImportPlanner.Result result = ImportPlanner.plan(
                List.of(candidate("diamond", new BigDecimal("100"))), List.of(), List.of(), MATERIALS, false,
                false);
        assertEquals(1, result.imported());
        assertEquals(0, result.existing());
        PriceEntry entry = result.write().getFirst();
        assertEquals("diamond", entry.id());
        assertEquals(new BigDecimal("100"), entry.price());
        assertTrue(entry.isPlain());
    }

    @Test
    void anAlreadyPricedItemIsSkippedWithoutOverwrite() {
        List<PriceEntry> existing = List.of(PriceEntry.plain("diamond", new BigDecimal("90")));
        ImportPlanner.Result result = ImportPlanner.plan(
                List.of(candidate("diamond", new BigDecimal("100"))), List.of(), existing, MATERIALS, false,
                false);
        assertEquals(0, result.imported());
        assertEquals(1, result.existing());
        assertTrue(result.write().isEmpty());
    }

    @Test
    void overwriteKeepsTheExistingIdAndReplacesThePrice() {
        List<PriceEntry> existing = List.of(new PriceEntry("custom_id", "diamond", new BigDecimal("90"),
                null, null, null, null, Set.of()));
        ImportPlanner.Result result = ImportPlanner.plan(
                List.of(candidate("diamond", new BigDecimal("100"))), List.of(), existing, MATERIALS, false, true);
        assertEquals(1, result.updated());
        assertEquals("custom_id", result.write().getFirst().id());
        assertEquals(new BigDecimal("100"), result.write().getFirst().price());
    }

    @Test
    void aNamedItemGetsItsOwnIdAndDoesNotReplaceThePlainEntry() {
        List<PriceEntry> existing = List.of(PriceEntry.plain("diamond", new BigDecimal("90")));
        ImportPlanner.Result result = ImportPlanner.plan(
                List.of(named("diamond", "Gem", new BigDecimal("500"))), List.of(), existing, MATERIALS, false,
                false);
        assertEquals(1, result.imported());
        assertEquals("diamond-1", result.write().getFirst().id());
        assertEquals("Gem", result.write().getFirst().name());
    }

    @Test
    void duplicateLinesOfOneImportAreReportedOnce() {
        ImportPlanner.Result result = ImportPlanner.plan(
                List.of(candidate("diamond", new BigDecimal("100")), candidate("diamond", new BigDecimal("120"))),
                List.of(), List.of(), MATERIALS, false, false);
        assertEquals(1, result.write().size());
        assertEquals(1, result.skipped().size());
        assertEquals("duplicate", result.skipped().getFirst().reason());
    }

    @Test
    void unknownMaterialsAreReportedAndLegacyNamesResolveOnlyWhenAllowed() {
        ImportPlanner.Result strict = ImportPlanner.plan(List.of(candidate("diamondpickaxe", BigDecimal.ONE)),
                List.of(), List.of(), MATERIALS, false, false);
        assertTrue(strict.write().isEmpty());
        assertEquals("unknown-material", strict.skipped().getFirst().reason());

        ImportPlanner.Result legacy = ImportPlanner.plan(List.of(candidate("diamondpickaxe", BigDecimal.TEN)),
                List.of(), List.of(), MATERIALS, true, false);
        assertEquals("diamond_pickaxe", legacy.write().getFirst().material());
    }

    @Test
    void anIdAlreadyUsedByAnotherMaterialIsNotOverwritten() {
        List<PriceEntry> existing = List.of(new PriceEntry("diamond", "emerald", new BigDecimal("5"),
                null, null, null, null, Set.of()));
        ImportPlanner.Result result = ImportPlanner.plan(
                List.of(candidate("diamond", new BigDecimal("100"))), List.of(), existing, MATERIALS, false,
                false);
        assertEquals("diamond-1", result.write().getFirst().id());
    }
}
