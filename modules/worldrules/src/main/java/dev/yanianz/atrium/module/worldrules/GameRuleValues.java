package dev.yanianz.atrium.module.worldrules;

import java.util.Locale;

/**
 * Strict game-rule value parsing. Deliberately rejects anything else for booleans: "yes" or a typo must
 * not silently become false (a bug in other plugins). Numeric rules are parsed as a full 32-bit integer.
 */
final class GameRuleValues {
    private GameRuleValues() {}

    enum Kind {
        BOOLEAN,
        INTEGER,
        UNSUPPORTED
    }

    static Kind kindOf(Class<?> type) {
        if (type == Boolean.class || type == boolean.class) {
            return Kind.BOOLEAN;
        }
        if (type == Integer.class || type == int.class) {
            return Kind.INTEGER;
        }
        return Kind.UNSUPPORTED;
    }

    static Object parse(Class<?> type, String raw) throws IllegalArgumentException {
        Kind kind = kindOf(type);
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        return switch (kind) {
            case BOOLEAN -> {
                if (text.equalsIgnoreCase("true")) {
                    yield Boolean.TRUE;
                }
                if (text.equalsIgnoreCase("false")) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException("not a boolean: " + text);
            }
            case INTEGER -> {
                try {
                    yield Integer.valueOf(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not an integer: " + text, e);
                }
            }
            case UNSUPPORTED -> throw new IllegalArgumentException(
                    "unsupported game rule type " + (type == null ? "null" : type.getSimpleName()));
        };
    }

    /** Example value for tab completion and error messages. */
    static String example(Class<?> type) {
        return switch (kindOf(type)) {
            case BOOLEAN -> "true";
            case INTEGER -> "0";
            case UNSUPPORTED -> "";
        };
    }

    static String describe(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }
}
