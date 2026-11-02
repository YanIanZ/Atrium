package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.core.util.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Parsing of the free-text tail of moderation commands: flags, optional jail/duration, reason presets. */
final class ArgParser {
    static final List<String> SILENT_FLAGS = List.of("-s", "-silent");
    static final String SHADOW_FLAG = "-shadow";

    private ArgParser() {}

    /** Tail of a command with the flags removed. {@code words} keeps the order of the remaining words. */
    record Parsed(boolean silent, boolean shadow, List<String> words) {
        String text() {
            return String.join(" ", words);
        }
    }

    static Parsed parse(@Nullable String tail) {
        boolean silent = false;
        boolean shadow = false;
        List<String> words = new ArrayList<>();
        if (tail != null) {
            for (String word : tail.strip().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                String lower = word.toLowerCase(Locale.ROOT);
                if (SILENT_FLAGS.contains(lower)) {
                    silent = true;
                } else if (lower.equals(SHADOW_FLAG)) {
                    shadow = true;
                } else {
                    words.add(word);
                }
            }
        }
        return new Parsed(silent, shadow, List.copyOf(words));
    }

    /** {@code /jail <player> [jail] [duration] [reason...]}: jail and duration are recognised only in that order. */
    record JailArgs(@Nullable String jail, @Nullable Duration duration, String reason) {}

    static JailArgs jailArgs(List<String> words, Collection<String> jailNames) {
        int index = 0;
        String jail = null;
        if (index < words.size()) {
            String candidate = words.get(index);
            for (String name : jailNames) {
                if (name.equalsIgnoreCase(candidate)) {
                    jail = name;
                    index++;
                    break;
                }
            }
        }
        Duration duration = null;
        if (index < words.size()) {
            Optional<Duration> parsed = parseDuration(words.get(index));
            if (parsed.isPresent()) {
                duration = parsed.get();
                index++;
            }
        }
        return new JailArgs(jail, duration, String.join(" ", words.subList(index, words.size())));
    }

    /** A positive duration such as {@code 30m} or {@code 1d12h}; bare numbers are not accepted here (ambiguous). */
    static Optional<Duration> parseDuration(String word) {
        if (word.isEmpty() || word.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Durations.parse(word).filter(d -> !d.isZero() && !d.isNegative());
    }

    /**
     * Final reason: blank becomes {@code defaultReason}; a reason that is exactly a preset key (case-insensitive,
     * with or without a leading {@code #}) becomes the preset text; anything else is kept and capped at
     * {@code maxLength} characters.
     */
    static String reason(String raw, Map<String, String> presets, String defaultReason, int maxLength) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            text = defaultReason;
        } else {
            String key = text.startsWith("#") ? text.substring(1) : text;
            for (Map.Entry<String, String> preset : presets.entrySet()) {
                if (preset.getKey().equalsIgnoreCase(key)) {
                    text = preset.getValue();
                    break;
                }
            }
        }
        int limit = Math.max(1, maxLength);
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
