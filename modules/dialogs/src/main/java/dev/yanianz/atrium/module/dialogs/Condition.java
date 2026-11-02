package dev.yanianz.atrium.module.dialogs;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * One condition on a dialog or a button: a permission node or a placeholder comparison. Pure, so the parsing
 * and comparison rules are unit tested without a server or PlaceholderAPI.
 *
 * <p>Syntax (one condition per YAML list entry):
 * <ul>
 *   <li>{@code permission:atrium.dialog.vip} (or {@code perm:...}): the viewer must have it.</li>
 *   <li>{@code %server_online% >= 5}, {@code %player_health% < 10}: a placeholder compared with a literal.</li>
 *   <li>{@code %vip%}: true when the placeholder reads as enabled/1/on/yes/true.</li>
 *   <li>{@code online >= 3}: a built-in value; no percent signs means the module's own value.</li>
 *   <li>A leading {@code !} negates the whole condition.</li>
 * </ul>
 *
 * <p>A placeholder that resolves to an empty string makes the condition false (the placeholder is missing or the
 * player has no value), never true: a broken expansion cannot open a gated dialog.
 */
record Condition(Type type, String key, @Nullable Operator op, @Nullable String expected, boolean negated,
        String source) {
    enum Type {
        PERMISSION,
        PLACEHOLDER
    }

    enum Operator {
        EQ("=="), NE("!="), GT(">"), GE(">="), LT("<"), LE("<=");

        final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        static @Nullable Operator of(String text) {
            for (Operator operator : values()) {
                if (operator.symbol.equals(text)) {
                    return operator;
                }
            }
            return null;
        }
    }

    /** Parses one condition; throws {@link IllegalArgumentException} with a readable reason when invalid. */
    static Condition parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("empty condition");
        }
        boolean negated = false;
        while (text.startsWith("!")) {
            negated = !negated;
            text = text.substring(1).strip();
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a '!' needs a condition after it");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("permission:") || lower.startsWith("perm:")) {
            String node = text.substring(text.indexOf(':') + 1).strip();
            if (!node.matches("[A-Za-z0-9_.*-]{1,128}")) {
                throw new IllegalArgumentException("'" + node + "' is not a valid permission node");
            }
            return new Condition(Type.PERMISSION, node, null, null, negated, raw.strip());
        }
        int at = operatorIndex(text);
        String key;
        @Nullable Operator operator;
        String expected;
        if (at < 0) {
            key = text;
            operator = null;
            expected = null;
        } else {
            key = text.substring(0, at).strip();
            String two = text.length() >= at + 2 ? text.substring(at, at + 2) : "";
            operator = Operator.of(two);
            int symbolLength = 2;
            if (operator == null) {
                operator = Operator.of(text.substring(at, at + 1));
                symbolLength = 1;
            }
            if (operator == null) {
                throw new IllegalArgumentException("unknown comparison in '" + raw.strip() + "'");
            }
            expected = text.substring(at + symbolLength).strip();
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("a placeholder condition needs a name before the comparison");
        }
        if (operator != null && expected.isEmpty()) {
            throw new IllegalArgumentException("a comparison needs a value after " + operator.symbol);
        }
        if (key.startsWith("%") && !key.endsWith("%")) {
            throw new IllegalArgumentException("a placeholder name must be wrapped in percent signs: " + key);
        }
        return new Condition(Type.PLACEHOLDER, key, operator, expected, negated, raw.strip());
    }

    /** Index of the first comparison operator, or -1. Length-2 operators are found at their first character. */
    private static int operatorIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '!' || c == '<' || c == '>' || c == '~') {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param hasPermission   the viewer's permissions
     * @param resolve         placeholder (or built-in) name to value; returning {@code null} means "unknown"
     */
    boolean test(Predicate<String> hasPermission, Function<String, String> resolve) {
        boolean result;
        if (type == Type.PERMISSION) {
            result = hasPermission.test(key);
        } else {
            String value = resolve.apply(key);
            result = compare(value == null ? "" : value);
        }
        return negated != result;
    }

    private boolean compare(String actual) {
        if (actual.isEmpty()) {
            return false;
        }
        if (op == null) {
            String lower = actual.strip().toLowerCase(Locale.ROOT);
            return lower.equals("enabled") || lower.equals("enable") || lower.equals("1") || lower.equals("on")
                    || lower.equals("yes") || lower.equals("true");
        }
        String expectedText = expected == null ? "" : expected;
        Double left = number(actual);
        Double right = number(expectedText);
        if (left != null && right != null) {
            return switch (op) {
                case EQ -> left.doubleValue() == right.doubleValue();
                case NE -> left.doubleValue() != right.doubleValue();
                case GT -> left > right;
                case GE -> left >= right;
                case LT -> left < right;
                case LE -> left <= right;
            };
        }
        return switch (op) {
            case EQ -> actual.strip().equalsIgnoreCase(expectedText.strip());
            case NE -> !actual.strip().equalsIgnoreCase(expectedText.strip());
            // Ordering a non-number is not meaningful; never guess.
            case GT, GE, LT, LE -> false;
        };
    }

    private static @Nullable Double number(String text) {
        try {
            double value = Double.parseDouble(text.strip());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
