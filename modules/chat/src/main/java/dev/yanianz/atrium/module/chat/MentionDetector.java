package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds player names in chat, either as {@code @name} or as plain whole words. */
final class MentionDetector {
    private MentionDetector() {}

    static Pattern pattern(Collection<String> names, boolean requireAtSign) {
        List<String> sorted = uniqueNames(names);
        if (sorted.isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (String name : sorted) {
            if (!regex.isEmpty()) {
                regex.append('|');
            }
            regex.append("(?<![\\p{L}\\p{N}_])");
            regex.append(requireAtSign ? "@" : "@?");
            regex.append(Pattern.quote(name));
            regex.append("(?![\\p{L}\\p{N}_])");
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Canonical names, in the order they appear, without duplicates. */
    static List<String> find(String text, Collection<String> names, boolean requireAtSign) {
        Pattern pattern = pattern(names, requireAtSign);
        if (pattern == null || text == null || text.isEmpty()) {
            return List.of();
        }
        Map<String, String> canonical = new LinkedHashMap<>();
        for (String name : uniqueNames(names)) {
            canonical.put(name.toLowerCase(Locale.ROOT), name);
        }
        Matcher matcher = pattern.matcher(text);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            String match = matcher.group();
            String name = match.startsWith("@") ? match.substring(1) : match;
            String resolved = canonical.get(name.toLowerCase(Locale.ROOT));
            if (resolved != null) {
                found.add(resolved);
            }
        }
        return List.copyOf(found);
    }

    private static List<String> uniqueNames(Collection<String> names) {
        if (names == null) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        List<String> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }
}
