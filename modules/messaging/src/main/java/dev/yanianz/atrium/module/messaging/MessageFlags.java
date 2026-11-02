package dev.yanianz.atrium.module.messaging;

import dev.yanianz.atrium.core.util.Durations;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leading flags of an admin message: {@code -t:<duration>}, {@code -s:<duration>} (the short CMI spelling of
 * the same duration), {@code -s} (silent), and {@code -t:<player>=<duration>} to give one target its own
 * duration. Flags are only recognised before the first word of the message, so a message may contain
 * {@code -s} or {@code -t:} as ordinary text.
 *
 * <p>Pure logic (no Bukkit) so the parsing rules can be unit tested.
 */
final class MessageFlags {
    private static final Pattern DURATION_FLAG = Pattern.compile("-([ts]):(\\S+)", Pattern.CASE_INSENSITIVE);

    enum Problem { NONE, BLANK, INVALID_DURATION, SILENT_NOT_ALLOWED }

    /**
     * {@code text} is the message with the leading flags removed.
     *
     * @param perTarget   target-specific durations, keyed by lower-cased player name
     */
    record Result(String text, int seconds, boolean silent, Map<String, Integer> perTarget, Problem problem,
            String detail) {
        Result {
            perTarget = Map.copyOf(perTarget);
        }

        boolean ok() {
            return problem == Problem.NONE;
        }

        /** Duration for one target: its own {@code -t:name=duration}, else the global one. */
        int secondsFor(String playerName) {
            Integer own = perTarget.get(playerName == null ? "" : playerName.toLowerCase(Locale.ROOT));
            return own == null ? seconds : own;
        }
    }

    private MessageFlags() {}

    static Result parse(String raw, int defaultSeconds, int maxSeconds, boolean silentAllowed) {
        return parse(raw, defaultSeconds, maxSeconds, silentAllowed, true);
    }

    /**
     * @param durationAllowed whether {@code -t:} / {@code -s:} are duration flags or ordinary message text
     */
    static Result parse(String raw, int defaultSeconds, int maxSeconds, boolean silentAllowed,
            boolean durationAllowed) {
        String rest = raw == null ? "" : raw.strip();
        boolean silent = false;
        int seconds = Math.max(1, defaultSeconds);
        int limit = Math.max(1, maxSeconds);
        Map<String, Integer> perTarget = new HashMap<>();
        while (!rest.isEmpty()) {
            int split = firstWhitespace(rest);
            String token = split < 0 ? rest : rest.substring(0, split);
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("-s") || lower.equals("-silent")) {
                if (!silentAllowed) {
                    return new Result("", seconds, true, perTarget, Problem.SILENT_NOT_ALLOWED, token);
                }
                silent = true;
            } else if (durationAllowed) {
                if (lower.equals("-t:") || lower.equals("-s:")) {
                    return new Result("", seconds, silent, perTarget, Problem.INVALID_DURATION, token);
                }
                Matcher duration = DURATION_FLAG.matcher(token);
                if (!duration.matches()) {
                    break;
                }
                String value = duration.group(2);
                int equals = value.indexOf('=');
                if (equals > 0) {
                    // -t:<player>=<duration>: a per-target override.
                    String name = value.substring(0, equals).strip().toLowerCase(Locale.ROOT);
                    String durationText = value.substring(equals + 1);
                    Optional<Duration> parsed = parseDuration(durationText, limit);
                    if (name.isEmpty() || parsed.isEmpty()) {
                        return new Result("", seconds, silent, perTarget, Problem.INVALID_DURATION, value);
                    }
                    perTarget.put(name, (int) Math.min(limit, parsed.get().toSeconds()));
                } else {
                    Optional<Duration> parsed = parseDuration(value, limit);
                    if (parsed.isEmpty()) {
                        return new Result("", seconds, silent, perTarget, Problem.INVALID_DURATION, value);
                    }
                    seconds = (int) Math.min(limit, parsed.get().toSeconds());
                }
            } else {
                break;
            }
            rest = split < 0 ? "" : rest.substring(split).strip();
        }
        if (rest.isBlank()) {
            return new Result("", seconds, silent, perTarget, Problem.BLANK, "");
        }
        return new Result(rest, seconds, silent, perTarget, Problem.NONE, "");
    }

    private static Optional<Duration> parseDuration(String text, int limit) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<Duration> parsed = Durations.parse(text);
        if (parsed.isEmpty() || parsed.get().isZero() || parsed.get().isNegative()) {
            return Optional.empty();
        }
        if (parsed.get().toSeconds() < 1) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(Math.min(limit, parsed.get().toSeconds())));
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
