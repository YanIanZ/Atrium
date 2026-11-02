package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Final per-category table of an import. It is generated from the same counters as the report file, so
 * the console and the file never disagree. Columns are padded for a monospace console font.
 */
final class ImportSummary {
    private static final String FORMAT = "%-12s %7s %9s %8s %8s %7s %11s";
    private static final int LABEL_WIDTH = 12;

    private ImportSummary() {}

    record Row(String label, long found, long imported, long skipped, long invalid, long failed, long unsupported) {
        Row plus(Row other) {
            return new Row(label, found + other.found, imported + other.imported, skipped + other.skipped,
                    invalid + other.invalid, failed + other.failed, unsupported + other.unsupported);
        }
    }

    static List<Row> rows(ImportReport report, ImportOptions options) {
        List<Row> rows = new ArrayList<>();
        Row total = new Row("total", 0, 0, 0, 0, 0, 0);
        for (ImportCategory category : ImportCategory.values()) {
            if (category == ImportCategory.SOURCE) {
                continue;
            }
            if (!options.includes(category)) {
                continue;
            }
            ImportReport.Counters counter = report.counters(category);
            Row row = new Row(label(category.key()), counter.found, counter.imported, counter.skipped,
                    counter.invalid, counter.failed, counter.unsupported);
            rows.add(row);
            total = total.plus(row);
        }
        // Row-level source problems are data problems; the final totals already include them.
        ImportReport.Counters source = report.counters(ImportCategory.SOURCE);
        total = total.plus(new Row("total", 0, 0, 0, source.invalid, source.failed, 0));
        rows.add(total);
        return List.copyOf(rows);
    }

    static String header() {
        return String.format(Locale.ROOT, FORMAT, "category", "found", "imported", "skipped", "invalid", "failed",
                "unsupported");
    }

    static String format(Row row) {
        return String.format(Locale.ROOT, FORMAT, row.label(), Long.toString(row.found()),
                Long.toString(row.imported()), Long.toString(row.skipped()), Long.toString(row.invalid()),
                Long.toString(row.failed()), Long.toString(row.unsupported()));
    }

    private static String label(String key) {
        return key.length() > LABEL_WIDTH ? key.substring(0, LABEL_WIDTH) : key;
    }
}
