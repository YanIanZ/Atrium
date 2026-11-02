package dev.yanianz.atrium.module.localeeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dotted message keys ({@code homes.no-homes}, {@code inventories.reason.death}) and the safe file names used to
 * address a language file. Pure, so the guard against {@code ..} or a stray slash is unit-tested.
 *
 * <p>A key is a non-empty list of segments; segments are letters, digits, {@code _} and {@code -} only. That is
 * exactly the shape the core {@code Messages} flattening produces, and it keeps a key safe in a command line and in
 * a file name.
 */
final class KeyPath {
    static final int MAX_SEGMENTS = 8;
    static final int MAX_SEGMENT_LENGTH = 64;
    static final int MAX_KEY_LENGTH = 200;

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]+");

    /** Why a key or name was refused. The command translates each reason. */
    enum Reason { EMPTY, EMPTY_SEGMENT, ILLEGAL_CHARACTER, TOO_LONG }

    static final class InvalidKeyException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;
        private final String text;

        InvalidKeyException(Reason reason, String text) {
            super("Invalid message key '" + text + "' (" + reason + ")");
            this.reason = reason;
            this.text = text;
        }

        Reason reason() {
            return reason;
        }

        String text() {
            return text;
        }
    }

    private KeyPath() {}

    /** Splits a dotted key; refuses empty segments, unsafe characters and absurd lengths. */
    static List<String> parse(String dotted) {
        if (dotted == null) {
            throw new InvalidKeyException(Reason.EMPTY, "");
        }
        String trimmed = dotted.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidKeyException(Reason.EMPTY, dotted);
        }
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw new InvalidKeyException(Reason.TOO_LONG, trimmed);
        }
        String[] raw = trimmed.split("\\.", -1);
        if (raw.length > MAX_SEGMENTS) {
            throw new InvalidKeyException(Reason.TOO_LONG, trimmed);
        }
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (segment.isEmpty()) {
                throw new InvalidKeyException(Reason.EMPTY_SEGMENT, trimmed);
            }
            if (segment.length() > MAX_SEGMENT_LENGTH) {
                throw new InvalidKeyException(Reason.TOO_LONG, trimmed);
            }
            if (!SAFE.matcher(segment).matches()) {
                throw new InvalidKeyException(Reason.ILLEGAL_CHARACTER, trimmed);
            }
            segments.add(segment);
        }
        return List.copyOf(segments);
    }

    static String join(List<String> path) {
        return String.join(".", path);
    }

    /** A locale folder or module bundle name: no separators, no {@code ..}, no empty name. */
    static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_SEGMENT_LENGTH
                && SAFE.matcher(name).matches();
    }

    /**
     * The canonical folder name of a locale ({@code en_us} becomes {@code en_US}). Throws when the result would not
     * be a single safe path segment, so a crafted argument can never escape the lang directory.
     */
    static String locale(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidKeyException(Reason.EMPTY, "");
        }
        String normalized = dev.yanianz.atrium.core.message.Messages.normalize(raw);
        if (!validName(normalized)) {
            throw new InvalidKeyException(Reason.ILLEGAL_CHARACTER, raw);
        }
        return normalized;
    }

    /** Lower-case helper for matching names and keys that came from the server. */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
