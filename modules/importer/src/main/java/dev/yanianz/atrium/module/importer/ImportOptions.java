package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsed arguments of {@code /atrium-import <source> [dry-run|run] [--only=...] [--overwrite]}.
 *
 * @param dryRun    true when nothing may be written
 * @param only      selected categories; never contains {@link ImportCategory#SOURCE}
 * @param overwrite replace existing items instead of skipping them
 */
record ImportOptions(boolean dryRun, Set<ImportCategory> only, boolean overwrite) {
    /** A parse failure the command layer can translate; the message stays readable in logs and tests. */
    static final class ParseException extends IllegalArgumentException {
        private final String reasonKey;
        private final String value;

        ParseException(String reasonKey, String value) {
            super(reasonKey + (value == null || value.isBlank() ? "" : ": " + value));
            this.reasonKey = reasonKey;
            this.value = value == null ? "" : value;
        }

        String reasonKey() {
            return reasonKey;
        }

        String value() {
            return value;
        }
    }

    ImportOptions {
        EnumSet<ImportCategory> selectable = EnumSet.copyOf(only);
        selectable.remove(ImportCategory.SOURCE);
        only = Collections.unmodifiableSet(selectable);
    }

    static ImportOptions defaults() {
        return defaults(ImportCategory.cmi());
    }

    static ImportOptions defaults(Set<ImportCategory> allowed) {
        return new ImportOptions(true, allowed, false);
    }

    boolean includes(ImportCategory category) {
        return category != ImportCategory.SOURCE && only.contains(category);
    }

    /** CMI parser: every CMI category is selectable. */
    static ImportOptions parse(String raw, boolean defaultDryRun) {
        return parse(raw, defaultDryRun, ImportCategory.cmi());
    }

    /**
     * Parses the optional arguments after the source name.
     *
     * @throws IllegalArgumentException with a short, user-facing reason
     */
    static ImportOptions parse(String raw, boolean defaultDryRun, Set<ImportCategory> allowed) {
        boolean dryRun = defaultDryRun;
        boolean overwrite = false;
        Set<ImportCategory> only = EnumSet.noneOf(ImportCategory.class);
        if (raw == null || raw.isBlank()) {
            only.addAll(allowed);
            return new ImportOptions(dryRun, only, false);
        }
        String[] tokens = raw.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("dry-run") || lower.equals("dryrun")) {
                dryRun = true;
            } else if (lower.equals("run")) {
                dryRun = false;
            } else if (lower.equals("--overwrite")) {
                overwrite = true;
            } else if (lower.startsWith("--only=")) {
                addOnly(only, token.substring("--only=".length()), allowed);
            } else if (lower.equals("--only")) {
                if (i + 1 >= tokens.length) {
                    throw new ParseException("missing-only-list", "");
                }
                addOnly(only, tokens[++i], allowed);
            } else {
                throw new ParseException("unknown-option", token);
            }
        }
        if (only.isEmpty()) {
            only.addAll(allowed);
        }
        return new ImportOptions(dryRun, only, overwrite);
    }

    private static void addOnly(Set<ImportCategory> only, String value, Set<ImportCategory> allowed) {
        for (String part : value.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            ImportCategory category = ImportCategory.byKey(part);
            if (category == null || !allowed.contains(category)) {
                throw new ParseException("unknown-category", part);
            }
            only.add(category);
        }
    }

    /**
     * Tab completion for the greedy argument. Suggestions replace the whole remaining argument, so each
     * one repeats the tokens the admin already typed.
     */
    static List<String> suggestions(String remaining, Set<ImportCategory> allowed) {
        String raw = remaining == null ? "" : remaining;
        int split = raw.lastIndexOf(' ');
        String prefix = split < 0 ? "" : raw.substring(0, split + 1);
        String last = split < 0 ? raw : raw.substring(split + 1);
        String lower = last.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (lower.startsWith("--only=")) {
            String typed = last.substring("--only=".length());
            int comma = typed.lastIndexOf(',');
            String before = comma < 0 ? "" : typed.substring(0, comma + 1);
            String partial = typed.substring(before.length()).toLowerCase(Locale.ROOT);
            Set<String> taken = new HashSet<>();
            for (String part : before.split(",")) {
                taken.add(part.trim().toLowerCase(Locale.ROOT));
            }
            for (ImportCategory category : allowed) {
                if (!taken.contains(category.key()) && category.key().startsWith(partial)) {
                    out.add(prefix + "--only=" + before + category.key());
                }
            }
            return out;
        }
        // The separated form: "--only ho" -> complete the category, not the option.
        String previous = prefix.isBlank() ? "" : prefix.substring(0, prefix.length() - 1).stripTrailing();
        int previousSpace = previous.lastIndexOf(' ');
        if ((previousSpace < 0 ? previous : previous.substring(previousSpace + 1)).equalsIgnoreCase("--only")) {
            for (ImportCategory category : allowed) {
                if (category.key().startsWith(lower)) {
                    out.add(prefix + category.key());
                }
            }
            return out;
        }
        for (String option : List.of("dry-run", "run", "--overwrite")) {
            if (option.startsWith(lower)) {
                out.add(prefix + option);
            }
        }
        if ("--only".startsWith(lower)) {
            out.add(prefix + "--only");
        }
        for (ImportCategory category : allowed) {
            String option = "--only=" + category.key();
            if (option.startsWith(lower)) {
                out.add(prefix + option);
            }
        }
        return out;
    }

    String describe() {
        return "mode=" + (dryRun ? "dry-run" : "run")
                + ", only=" + only.stream().map(ImportCategory::key).sorted().toList()
                + ", overwrite=" + overwrite;
    }
}
