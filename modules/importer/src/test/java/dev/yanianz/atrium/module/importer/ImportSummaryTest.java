package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportSummaryTest {
    @Test
    void buildsAPerCategoryTableWithATotalRow() {
        ImportReport report = new ImportReport();
        report.found(ImportCategory.ECONOMY, 3);
        report.imported(ImportCategory.ECONOMY, 2);
        report.skipped(ImportCategory.ECONOMY, 1);
        report.found(ImportCategory.HOMES, 4);
        report.imported(ImportCategory.HOMES, 3);
        report.failed(ImportCategory.HOMES, "steve", "write failed");
        report.invalid(ImportCategory.SOURCE, "broken", "invalid uuid");

        ImportOptions options = ImportOptions.defaults(ImportCategory.essentials());
        List<ImportSummary.Row> rows = ImportSummary.rows(report, options);

        ImportSummary.Row total = rows.getLast();
        assertEquals("total", total.label());
        assertEquals(5, total.imported());
        assertEquals(1, total.skipped());
        assertEquals(1, total.failed());
        assertEquals(1, total.invalid(), "row-level source problems belong to the totals");

        assertEquals(ImportSummary.header().length(), ImportSummary.format(total).length(),
                "every row must have the same width so a console table stays aligned");
        assertTrue(ImportSummary.format(rows.getFirst()).startsWith("economy"));
    }

    @Test
    void onlySelectedCategoriesAppear() {
        ImportReport report = new ImportReport();
        report.found(ImportCategory.HOLOGRAMS, 5);
        ImportOptions options = new ImportOptions(true, Set.of(ImportCategory.HOMES), false);
        List<ImportSummary.Row> rows = ImportSummary.rows(report, options);
        assertEquals(2, rows.size(), "one category row plus the total");
        assertEquals("homes", rows.getFirst().label());
    }
}
