package dev.yanianz.atrium.module.maps;

import java.util.Locale;

/**
 * Text handling for marker data.
 *
 * <p>Marker labels and details are HTML on BlueMap, squaremap and dynmap, and the strings that reach
 * them include warp and portal names written by players. Everything user-visible therefore goes
 * through {@link #escapeHtml(String)}. Marker ids are built from a readable slug plus a stable hash of
 * the original value, so two different names that slugify to the same text can never overwrite each
 * other's marker.
 */
public final class MapText {
    private static final int MAX_SLUG = 48;

    private MapText() {}

    /** Escapes {@code & < > " '} so a label cannot inject markup into a web map. Null becomes "". */
    public static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A lower-case id usable by every map backend: {@code [a-z0-9_-]}, at most 48 characters. Runs of
     * unsupported characters become a single {@code _}; leading and trailing separators are removed.
     * Blank input falls back to {@code fallback} (or {@code "marker"}).
     */
    public static String sanitizeId(String raw, String fallback) {
        String slug = slug(raw);
        if (!slug.isEmpty()) {
            return slug;
        }
        String fallbackSlug = slug(fallback);
        return fallbackSlug.isEmpty() ? "marker" : fallbackSlug;
    }

    /**
     * A collision-free marker id: {@code <prefix>_<slug>_<hash>} where the hash is computed from the
     * original, unmodified value. Deterministic across restarts.
     */
    public static String markerId(String prefix, String raw) {
        String safePrefix = sanitizeId(prefix, "marker");
        String value = raw == null ? "" : raw;
        String safeValue = sanitizeId(value, "marker");
        return safePrefix + '_' + safeValue + '_' + hash8(value);
    }

    private static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(Math.min(lower.length(), MAX_SLUG));
        boolean lastSeparator = false;
        for (int i = 0; i < lower.length() && out.length() < MAX_SLUG; i++) {
            char c = lower.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (alphanumeric) {
                out.append(c);
                lastSeparator = false;
            } else if (!lastSeparator) {
                out.append('_');
                lastSeparator = true;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && isSeparator(out.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(out.charAt(end - 1))) {
            end--;
        }
        return out.substring(start, end);
    }

    private static boolean isSeparator(char c) {
        return c == '-' || c == '_';
    }

    /** FNV-1a over the UTF-16 code units; eight lower-case hex digits. */
    private static String hash8(String value) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x01000193;
        }
        return String.format(Locale.ROOT, "%08x", hash);
    }
}
