package dev.yanianz.atrium.module.messaging;

import dev.yanianz.atrium.core.util.Durations;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leading flags of an admin message: {@code -t:<duration>} and {@code -s}. Flags are only recognised before
 * the first word of the message, so a message may contain {@code -s} or {@code -t:} as ordinary text.
 *
 * <p>Pure logic (no Bukkit) so the parsing rules can be unit tested.
 */
final class MessageFlags {
    private static final Pattern DURATION_FLAG = Pattern.compile("-t:(\\S+)", Pattern.CASE_INSENSITIVE);

    enum Problem { NONE, BLANK, INVALID_DURATION, SILENT_NOT_ALLOWED }

    /** {@code text} is the message with the leading flags removed. */
    record Result(String text, int seconds, boolean silent, Problem problem, String detail) {
        boolean ok() {
            return problem == Problem.NONE;
        }
    }

    private MessageFlags() {}

    /**
     * Parses leading flags.
     *
     * @param defaultSeconds duration used when no {@code -t:} flag is present
     * @param maxSeconds longest accepted duration; longer values are clamped
     * @param silentAllowed whether the sender may use {@code -s}
     */
    static Result parse(String raw, int defaultSeconds, int maxSeconds, boolean silentAllowed) {
        return parse(raw, defaultSeconds, maxSeconds, silentAllowed, true);
    }

    /**
     * Parses leading flags.
     *
     * @param defaultSeconds duration used when no {@code -t:} flag is present
     * @param maxSeconds longest accepted duration; longer values are clamped
     * @param silentAllowed whether the sender may use {@code -s}
     * @param durationAllowed whether {@code -t:<duration>} is a flag or ordinary message text
     */
    static Result parse(String raw, int defaultSeconds, int maxSeconds, boolean silentAllowed,
            boolean durationAllowed) {
        String rest = raw == null ? "" : raw.strip();
        boolean silent = false;
        int seconds = Math.max(1, defaultSeconds);
        int limit = Math.max(1, maxSeconds);
        while (!rest.isEmpty()) {
            int split = firstWhitespace(rest);
            String token = split < 0 ? rest : rest.substring(0, split);
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("-s") || lower.equals("-silent")) {
                if (!silentAllowed) {
                    return new Result("", seconds, true, Problem.SILENT_NOT_ALLOWED, token);
                }
                silent = true;
            } else if (durationAllowed) {
                if (lower.equals("-t:")) {
                    return new Result("", seconds, silent, Problem.INVALID_DURATION, token);
                }
                Matcher duration = DURATION_FLAG.matcher(token);
                if (duration.matches()) {
                    Optional<Duration> parsed = Durations.parse(duration.group(1));
                    if (parsed.isEmpty() || parsed.get().isZero() || parsed.get().isNegative()) {
                        return new Result("", seconds, silent, Problem.INVALID_DURATION, duration.group(1));
                    }
                    long value = parsed.get().toSeconds();
                    if (value < 1) {
                        return new Result("", seconds, silent, Problem.INVALID_DURATION, duration.group(1));
                    }
                    seconds = (int) Math.min(limit, value);
                } else {
                    break;
                }
            } else {
                break;
            }
            rest = split < 0 ? "" : rest.substring(split).strip();
        }
        if (rest.isBlank()) {
            return new Result("", seconds, silent, Problem.BLANK, "");
        }
        return new Result(rest, seconds, silent, Problem.NONE, "");
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
