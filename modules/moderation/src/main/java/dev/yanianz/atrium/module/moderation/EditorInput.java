package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.core.util.Durations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Validation of the values typed into {@code /editwarnings} and {@code /jailedit}. Pure arithmetic and
 * string handling so that the rules can be unit tested without a server. Nothing here touches storage or
 * the world; the commands resolve accounts and save the result themselves.
 *
 * <p>Every value is checked here before it is stored, which prevents the classic editor bugs (id {@code <= 0},
 * unvalidated jail names, prefix command lists) were missing.
 */
final class EditorInput {
    /** A command entry: 1-8 lower-case words, no wildcard, no leading slash. */
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9:_-]{1,32}(\\s+[a-z0-9:_-]{1,32}){0,7}");
    private static final List<String> PERMANENT = List.of("permanent", "never", "none", "off", "0");

    private EditorInput() {}

    /** Punishment ids are positive; 0 and negatives can never name a stored record. */
    static boolean validId(long id) {
        return id > 0;
    }

    /** Trimmed reason text, or empty when nothing usable was typed. Capped like every other reason. */
    static Optional<String> reason(@Nullable String raw, int maxLength) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        int limit = Math.max(1, maxLength);
        return Optional.of(text.length() > limit ? text.substring(0, limit) : text);
    }

    /** A new expiry. {@code expires == null} means "permanent"; an empty Optional means "not a duration". */
    record Expiry(@Nullable Instant expires) {
        boolean permanent() {
            return expires == null;
        }
    }

    /**
     * Parses {@code 30m}, {@code 7d}, {@code permanent}, {@code never}, {@code none}, {@code off} or {@code 0}.
     * Relative durations are added to {@code now}; zero and negative durations are rejected.
     */
    static Optional<Expiry> expiry(@Nullable String raw, Instant now) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (PERMANENT.contains(text)) {
            return Optional.of(new Expiry(null));
        }
        return Durations.parse(text)
                .filter(d -> !d.isZero() && !d.isNegative())
                .map(d -> new Expiry(now.plus(d)));
    }

    /** A per-jail allowed command, normalised to lower case. {@code *} is not accepted (it allows everything). */
    static Optional<String> allowedCommand(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        text = text.replaceAll("\\s+", " ");
        if (text.isEmpty() || text.equals("*") || !COMMAND.matcher(text).matches()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** {@code true}/{@code false} with the usual aliases; empty for anything else. */
    static Optional<Boolean> bool(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Optional.of(true);
            case "false", "off", "no", "0" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** Maximum sentence of a jail. {@code value == null} means "no maximum". Empty means "invalid". */
    record MaxSentence(@Nullable Duration value) {
        boolean unlimited() {
            return value == null;
        }
    }

    static Optional<MaxSentence> maxSentence(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (PERMANENT.contains(text)) {
            return Optional.of(new MaxSentence(null));
        }
        return Durations.parse(text)
                .filter(d -> !d.isZero() && !d.isNegative())
                .map(d -> new MaxSentence(d));
    }

    /**
     * A jail sentence capped to the maximum of the jail. A permanent request becomes the maximum when one
     * is configured, and a longer finite request is shortened; {@code null} max leaves the request alone.
     */
    static @Nullable Duration cap(@Nullable Duration requested, @Nullable Duration max) {
        if (max == null) {
            return requested;
        }
        if (requested == null) {
            return max;
        }
        return requested.compareTo(max) > 0 ? max : requested;
    }
}
