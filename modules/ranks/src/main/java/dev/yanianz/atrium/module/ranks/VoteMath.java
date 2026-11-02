package dev.yanianz.atrium.module.ranks;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Arithmetic and name rules of {@code /voteedit}; pure and unit tested. */
final class VoteMath {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private VoteMath() {}

    static Optional<String> normalizeName(String raw) {
        if (raw == null || !NAME.matcher(raw.strip()).matches()) {
            return Optional.empty();
        }
        return Optional.of(raw.strip().toLowerCase(Locale.ROOT));
    }

    static boolean validName(String raw) {
        return raw != null && NAME.matcher(raw.strip()).matches();
    }

    static long set(long amount) {
        return Math.max(0L, amount);
    }

    static long add(long current, long amount) {
        long base = Math.max(0L, current);
        long delta = Math.max(0L, amount);
        return base > Long.MAX_VALUE - delta ? Long.MAX_VALUE : base + delta;
    }

    static long take(long current, long amount) {
        long base = Math.max(0L, current);
        return base - Math.min(base, Math.max(0L, amount));
    }

    static int pages(long total, int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 1;
        }
        return (int) Math.max(1L, (total + pageSize - 1L) / pageSize);
    }

    static int clampPage(int page, int pages) {
        return Math.max(1, Math.min(Math.max(1, pages), page));
    }

    static int offset(int page, int pageSize) {
        long offset = (long) (Math.max(1, page) - 1) * Math.max(0, pageSize);
        return (int) Math.min(Integer.MAX_VALUE, offset);
    }
}
