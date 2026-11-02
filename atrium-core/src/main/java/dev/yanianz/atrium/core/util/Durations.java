package dev.yanianz.atrium.core.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats short durations such as {@code 1d2h30m}, {@code 45s}, {@code 2w}. */
public final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(ms|mo|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL = Pattern.compile("(\\s*\\d+\\s*(ms|mo|w|d|h|m|s))+\\s*", Pattern.CASE_INSENSITIVE);

    private Durations() {}

    /** Parses {@code 1d2h}, or a bare number of seconds. Empty for invalid or negative input. */
    public static Optional<Duration> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(text)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        if (!FULL.matcher(text).matches()) {
            return Optional.empty();
        }
        Duration total = Duration.ZERO;
        Matcher m = PART.matcher(text);
        try {
            while (m.find()) {
                long n = Long.parseLong(m.group(1));
                total = total.plus(switch (m.group(2)) {
                    case "mo" -> Duration.ofDays(Math.multiplyExact(n, 30));
                    case "w" -> Duration.ofDays(Math.multiplyExact(n, 7));
                    case "d" -> Duration.ofDays(n);
                    case "h" -> Duration.ofHours(n);
                    case "m" -> Duration.ofMinutes(n);
                    case "s" -> Duration.ofSeconds(n);
                    case "ms" -> Duration.ofMillis(n);
                    default -> throw new IllegalStateException(m.group(2));
                });
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(total);
    }

    /** Compact format, e.g. {@code 1d 2h 5m}; at most {@code maxUnits} units, seconds when under a minute. */
    public static String format(Duration duration, int maxUnits) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds == 0) {
            return "0s";
        }
        long[] values = {seconds / 86_400, seconds % 86_400 / 3_600, seconds % 3_600 / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < values.length && used < maxUnits; i++) {
            if (values[i] > 0) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(values[i]).append(units[i]);
                used++;
            }
        }
        return out.toString();
    }
}
