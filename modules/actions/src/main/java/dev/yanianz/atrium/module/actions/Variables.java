package dev.yanianz.atrium.module.actions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * {@code {name}} variables. Pure, unit tested.
 *
 * <ul>
 *   <li>Commands and other strict arguments: values must be safe (see {@link #isSafe}); a reference to an unknown
 *       variable is rejected too, so a broken command never runs.</li>
 *   <li>Messages: values are inserted as plain text components; MiniMessage in a value is never parsed.</li>
 * </ul>
 * Substitution is one left-to-right pass over the template: inserted values are never scanned again, so a value
 * like {@code "{uuid}"} stays literal.
 */
final class Variables {
    static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
    static final Pattern REFERENCE = Pattern.compile("\\{([a-z0-9_]{1,32})}");
    static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** Letters, digits, single spaces and a few punctuation marks. No selectors (@), quotes, braces, %, §, & or slashes. */
    static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_.,:+\\-!?]+( [A-Za-z0-9_.,:+\\-!?]+)*");
    static final int MAX_VALUE_LENGTH = 256;
    /** Variables whose value is a player name and must look like one. */
    static final Set<String> PLAYER_VARIABLES = Set.of("player");
    static final Set<String> BUILT_INS = Set.of("player", "uuid", "world", "x", "y", "z");

    private static final String TAG = "atrium_var";

    private Variables() {}

    /** Thrown when a value may not be inserted into a command. */
    static final class UnsafeValueException extends Exception {
        private static final long serialVersionUID = 1L;
        final String variable;

        UnsafeValueException(String variable, String reason) {
            super("variable {" + variable + "} " + reason);
            this.variable = variable;
        }
    }

    static boolean hasReference(String text) {
        return REFERENCE.matcher(text).find();
    }

    /**
     * Built-ins plus caller variables. Built-ins win; caller keys are lower-cased and keys that are not
     * {@code [a-z0-9_]{1,32}} are dropped.
     */
    static Map<String, String> merge(Map<String, String> builtIns, Map<String, String> caller) {
        Map<String, String> out = new HashMap<>();
        if (caller != null) {
            caller.forEach((key, value) -> {
                if (key != null && value != null) {
                    String k = key.toLowerCase(Locale.ROOT);
                    if (KEY.matcher(k).matches() && !BUILT_INS.contains(k)) {
                        out.put(k, value);
                    }
                }
            });
        }
        out.putAll(builtIns);
        return out;
    }

    /** Whether {@code value} may be inserted for {@code key} into a command. */
    static boolean isSafe(String key, String value) {
        if (PLAYER_VARIABLES.contains(key)) {
            return PLAYER_NAME.matcher(value).matches();
        }
        return value.isEmpty() || (value.length() <= MAX_VALUE_LENGTH && SAFE_VALUE.matcher(value).matches());
    }

    /** Substitutes into a command or other strict argument. Every referenced value must be known and safe. */
    static String substituteStrict(String template, Map<String, String> vars) throws UnsafeValueException {
        Matcher m = REFERENCE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int last = 0;
        while (m.find()) {
            String key = m.group(1);
            String value = vars.get(key);
            if (value == null) {
                throw new UnsafeValueException(key, "is not defined here");
            }
            if (!isSafe(key, value)) {
                throw new UnsafeValueException(key, "contains characters that are not allowed in a command");
            }
            out.append(template, last, m.start()).append(value);
            last = m.end();
        }
        return out.append(template, last, template.length()).toString();
    }

    /**
     * Converts a trusted MiniMessage template: known {@code {name}} references become a placeholder tag that
     * {@link #resolver} fills with plain text. Unknown references stay literal.
     */
    static String toMiniMessage(String template, Map<String, String> vars) {
        Matcher m = REFERENCE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            if (vars.containsKey(m.group(1))) {
                out.append('<').append(TAG).append(':').append(m.group(1)).append('>');
            } else {
                out.append(m.group());
            }
            last = m.end();
        }
        return out.append(template, last, template.length()).toString();
    }

    /** Resolves the tags produced by {@link #toMiniMessage}: values are unparsed text. */
    static TagResolver resolver(Map<String, String> vars) {
        Map<String, String> copy = Map.copyOf(vars);
        return TagResolver.resolver(TAG, (args, context) -> {
            String key = args.popOr("variable name expected").value();
            String value = copy.get(key);
            return Tag.selfClosingInserting(Component.text(value == null ? "" : value));
        });
    }
}
