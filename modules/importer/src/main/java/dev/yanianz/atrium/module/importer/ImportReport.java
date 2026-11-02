package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Counts and problems of one import run. All mutation happens on the job's single I/O thread; snapshots
 * are taken only after the job finished, so no locking is needed.
 */
final class ImportReport {
    /** Keep a pathological source from exhausting memory; the counters remain exact. */
    private static final int MAX_ISSUES_PER_CATEGORY = 10_000;

    static final class Counters {
        long found;
        long imported;
        long skipped;
        long invalid;
        long failed;
        long unsupported;
        long extra;

        Counters copy() {
            Counters copy = new Counters();
            copy.found = found;
            copy.imported = imported;
            copy.skipped = skipped;
            copy.invalid = invalid;
            copy.failed = failed;
            copy.unsupported = unsupported;
            copy.extra = extra;
            return copy;
        }
    }

    private final Map<ImportCategory, Counters> counters = new EnumMap<>(ImportCategory.class);
    private final Map<ImportCategory, List<ImportIssue>> issues = new EnumMap<>(ImportCategory.class);
    private final Map<ImportCategory, Boolean> truncated = new EnumMap<>(ImportCategory.class);
    private final List<String> notes = new ArrayList<>();
    /** Per category: "skipped: reason" / "unsupported: reason" -> count, in first-seen order. */
    private final Map<ImportCategory, Map<String, Long>> reasons = new EnumMap<>(ImportCategory.class);

    ImportReport() {
        for (ImportCategory category : ImportCategory.values()) {
            counters.put(category, new Counters());
            issues.put(category, new ArrayList<>());
            reasons.put(category, new java.util.LinkedHashMap<>());
        }
    }

    Counters counters(ImportCategory category) {
        return counters.get(category);
    }

    List<ImportIssue> issues(ImportCategory category) {
        return List.copyOf(issues.get(category));
    }

    List<String> notes() {
        return List.copyOf(notes);
    }

    void note(String note) {
        notes.add(note);
    }

    void found(ImportCategory category, long count) {
        counters.get(category).found += count;
    }

    void imported(ImportCategory category, long count) {
        counters.get(category).imported += count;
    }

    void skipped(ImportCategory category, long count) {
        counters.get(category).skipped += count;
    }

    void unsupported(ImportCategory category, long count) {
        counters.get(category).unsupported += count;
    }

    /** Skipped with a reason that is tallied per category in the report and the summary. */
    void skipped(ImportCategory category, long count, String reason) {
        if (count <= 0) {
            return;
        }
        skipped(category, count);
        tally(category, "skipped", reason, count);
    }

    /** Not imported because Atrium cannot store it; the reason is tallied like {@link #skipped}. */
    void unsupported(ImportCategory category, long count, String reason) {
        if (count <= 0) {
            return;
        }
        unsupported(category, count);
        tally(category, "unsupported", reason, count);
    }

    private void tally(ImportCategory category, String kind, String reason, long count) {
        String key = kind + ": " + (reason == null || reason.isBlank() ? "no reason given" : reason);
        reasons.get(category).merge(key, count, Long::sum);
    }

    /** "skipped: reason" / "unsupported: reason" -> count, in first-seen order. */
    Map<String, Long> reasons(ImportCategory category) {
        return java.util.Collections.unmodifiableMap(reasons.get(category));
    }

    void extra(ImportCategory category, long value) {
        counters.get(category).extra += value;
    }

    void invalid(ImportCategory category, String subject, String message) {
        counters.get(category).invalid++;
        addIssue(new ImportIssue(category, subject, message));
    }

    void failed(ImportCategory category, String subject, String message) {
        failed(category, 1, subject, message);
    }

    void failed(ImportCategory category, long count, String subject, String message) {
        counters.get(category).failed += Math.max(1, count);
        addIssue(new ImportIssue(category, subject, message));
    }

    void issue(ImportIssue issue) {
        addIssue(issue);
    }

    private void addIssue(ImportIssue issue) {
        List<ImportIssue> list = issues.get(issue.category());
        if (list.size() < MAX_ISSUES_PER_CATEGORY) {
            list.add(issue);
        } else {
            truncated.put(issue.category(), true);
        }
    }

    String render(String title, ImportOptions options, String source, boolean cancelled, long elapsedMillis,
            String reportPath) {
        StringBuilder out = new StringBuilder(4096);
        out.append("Atrium ").append(title).append(" import report\n");
        out.append("Mode: ").append(options.dryRun() ? "dry-run" : "run").append('\n');
        out.append("Source: ").append(source).append('\n');
        out.append("Options: ").append(options.describe()).append('\n');
        out.append("Cancelled: ").append(cancelled).append('\n');
        out.append(String.format(java.util.Locale.ROOT, "Elapsed: %.2f s%n", elapsedMillis / 1000.0));
        if (reportPath != null) {
            out.append("Report: ").append(reportPath).append('\n');
        }
        out.append('\n');
        if (options.dryRun()) {
            out.append("Note: dry-run: 'imported' counts items that would be imported.\n");
        }
        for (String note : notes) {
            out.append("Note: ").append(note).append('\n');
        }
        out.append('\n');
        out.append(ImportSummary.header()).append('\n');
        for (ImportSummary.Row row : ImportSummary.rows(this, options)) {
            out.append(ImportSummary.format(row)).append('\n');
        }
        out.append('\n');
        for (ImportCategory category : ImportCategory.values()) {
            if (category == ImportCategory.SOURCE) {
                continue;
            }
            if (!options.includes(category)) {
                continue;
            }
            Counters counter = counters.get(category);
            out.append(category.key()).append(": found=").append(counter.found)
                    .append(" imported=").append(counter.imported)
                    .append(" skipped=").append(counter.skipped)
                    .append(" invalid=").append(counter.invalid)
                    .append(" failed=").append(counter.failed)
                    .append(" unsupported=").append(counter.unsupported);
            if (counter.extra != 0) {
                out.append(" extra=").append(counter.extra);
            }
            out.append('\n');
            for (Map.Entry<String, Long> reason : reasons.get(category).entrySet()) {
                out.append("  ").append(reason.getKey()).append(" (").append(reason.getValue()).append(")\n");
            }
            if (counter.invalid + counter.failed > 0) {
                out.append("  invalid/failed: see 'Problems for ").append(category.key()).append("' below\n");
            }
        }
        out.append('\n');
        for (Map.Entry<ImportCategory, List<ImportIssue>> entry : issues.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            out.append("Problems for ").append(entry.getKey().key()).append(" (").append(entry.getValue().size())
                    .append("):\n");
            for (ImportIssue issue : entry.getValue()) {
                out.append("  ").append(issue.format()).append('\n');
            }
            if (truncated.getOrDefault(entry.getKey(), false)) {
                out.append("  ... more problems omitted (limit ").append(MAX_ISSUES_PER_CATEGORY).append(")\n");
            }
        }
        return out.toString();
    }
}
