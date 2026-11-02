package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportOptionsTest {
    @Test
    void defaultsToDryRunAllCategories() {
        ImportOptions options = ImportOptions.parse("", true);
        assertTrue(options.dryRun());
        assertEquals(ImportCategory.cmi(), options.only());
        assertTrue(options.only().containsAll(java.util.EnumSet.range(ImportCategory.ECONOMY, ImportCategory.HOLOGRAMS)));
        assertFalse(options.only().contains(ImportCategory.SOURCE));
        assertFalse(options.overwrite());
    }

    @Test
    void parsesModeOnlyAndOverwrite() {
        ImportOptions options = ImportOptions.parse("run --only=homes,nicks --overwrite", true);
        assertFalse(options.dryRun());
        assertTrue(options.only().contains(ImportCategory.HOMES));
        assertTrue(options.only().contains(ImportCategory.NICKS));
        assertFalse(options.only().contains(ImportCategory.WARPS));
        assertTrue(options.overwrite());
    }

    @Test
    void acceptsTheSeparatedOnlyForm() {
        ImportOptions options = ImportOptions.parse("dry-run --only warps", false);
        assertTrue(options.dryRun());
        assertEquals(Set.of(ImportCategory.WARPS), options.only());
    }

    @Test
    void rejectsUnknownTokens() {
        assertThrows(IllegalArgumentException.class, () -> ImportOptions.parse("--nope", true));
        assertThrows(IllegalArgumentException.class, () -> ImportOptions.parse("--only=unknown", true));
        assertThrows(IllegalArgumentException.class, () -> ImportOptions.parse("--only", true));
    }

    @Test
    void onlyAcceptsCategoriesOfTheChosenSource() {
        ImportOptions essentials = ImportOptions.parse("", true, ImportCategory.essentials());
        assertTrue(essentials.only().contains(ImportCategory.SPAWN));
        assertTrue(essentials.only().contains(ImportCategory.PUNISHMENTS));
        assertTrue(essentials.only().contains(ImportCategory.MAIL));
        // The old category name still selects punishments.
        assertEquals(Set.of(ImportCategory.PUNISHMENTS),
                ImportOptions.parse("--only=states", true, ImportCategory.essentials()).only());
        assertFalse(essentials.only().contains(ImportCategory.HOLOGRAMS));
        assertThrows(IllegalArgumentException.class,
                () -> ImportOptions.parse("--only=holograms", true, ImportCategory.essentials()));

        ImportOptions cmi = ImportOptions.parse("--only=warps", true, ImportCategory.cmi());
        assertEquals(Set.of(ImportCategory.WARPS), cmi.only());
        assertEquals(Set.of(ImportCategory.SPAWN, ImportCategory.JAILS),
                ImportOptions.parse("--only=spawn,jails", true, ImportCategory.cmi()).only());
        assertThrows(IllegalArgumentException.class,
                () -> ImportOptions.parse("--only=mail", true, ImportCategory.cmi()));
        assertThrows(IllegalArgumentException.class,
                () -> ImportOptions.parse("--only=homes", true, ImportCategory.economyOnly()));
    }

    @Test
    void reportsTranslatableParseReasons() {
        ImportOptions.ParseException option = assertThrows(ImportOptions.ParseException.class,
                () -> ImportOptions.parse("--nope", true));
        assertEquals("unknown-option", option.reasonKey());
        assertEquals("--nope", option.value());

        ImportOptions.ParseException category = assertThrows(ImportOptions.ParseException.class,
                () -> ImportOptions.parse("--only=unknown", true, ImportCategory.essentials()));
        assertEquals("unknown-category", category.reasonKey());
        assertEquals("unknown", category.value());

        assertEquals("missing-only-list", assertThrows(ImportOptions.ParseException.class,
                () -> ImportOptions.parse("--only", true)).reasonKey());
    }

    @Test
    void suggestsEveryAllowedCategoryAfterOnly() {
        List<String> options = ImportOptions.suggestions("--o", ImportCategory.essentials());
        assertTrue(options.contains("--only"));
        assertTrue(options.contains("--only=homes"));
        assertTrue(options.contains("--only=punishments"));
        assertTrue(options.contains("--only=mail"));
        assertFalse(options.contains("--only=holograms"));

        assertTrue(ImportOptions.suggestions("dry-run --only ho", ImportCategory.essentials())
                .contains("dry-run --only homes"));
    }

    @Test
    void suggestsOptionsAndCategoriesForTheGreedyArgument() {
        List<String> first = ImportOptions.suggestions("dr", ImportCategory.essentials());
        assertTrue(first.contains("dry-run"));
        assertFalse(first.contains("run"));
        assertTrue(ImportOptions.suggestions("dry-run --on", ImportCategory.essentials())
                .contains("dry-run --only=economy"));
        assertTrue(ImportOptions.suggestions("--only=pun", ImportCategory.essentials())
                .contains("--only=punishments"));
        assertTrue(ImportOptions.suggestions("--only=homes,", ImportCategory.essentials())
                .contains("--only=homes,warps"));
        assertFalse(ImportOptions.suggestions("--only=ma", ImportCategory.cmi())
                .contains("--only=mail"));
    }
}
