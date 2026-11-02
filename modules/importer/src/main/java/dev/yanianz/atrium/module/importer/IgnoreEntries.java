package dev.yanianz.atrium.module.importer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Parses one source's ignore-list value. Pure, so the two shapes can be tested without a server.
 *
 * <p>EssentialsX writes a YAML list of UUIDs (older files: player names). CMI stores one column with the
 * UUIDs separated by {@code ;}; newer CMI may write a JSON array instead. Both end up as a list of trimmed,
 * de-duplicated entries; whether an entry is a UUID or a name is decided later, because a name has to be
 * resolved through the user service.
 */
final class IgnoreEntries {
    private IgnoreEntries() {}

    /** Entries of one raw value: {@code "a;b"}, {@code "a,b"} or {@code ["a","b"]}. */
    static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.strip();
        if (text.startsWith("[")) {
            List<String> fromJson = fromJson(text);
            if (fromJson != null) {
                return dedupe(fromJson);
            }
        }
        return dedupe(List.of(text.split("[;,]")));
    }

    /** {@code null} when the text is not a JSON array; a malformed one is treated as plain text. */
    private static List<String> fromJson(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    out.add(entry.getAsString());
                }
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Trims, drops blanks and removes duplicates. Names are compared case-insensitively because Minecraft
     * names are; UUIDs are already case-insensitive in {@link UUID#fromString(String)}.
     */
    static List<String> dedupe(Collection<String> raw) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String entry = value.strip();
            if (entry.isEmpty()) {
                continue;
            }
            unique.putIfAbsent(entry.toLowerCase(Locale.ROOT), entry);
        }
        return List.copyOf(unique.values());
    }

    /** The UUID an entry spells, or {@code null} when it is a player name. */
    static UUID uuid(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(entry.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
