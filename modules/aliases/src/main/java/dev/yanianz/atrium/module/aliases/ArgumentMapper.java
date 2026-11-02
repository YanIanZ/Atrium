package dev.yanianz.atrium.module.aliases;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps typed alias arguments to action variables, validating each value by its type. Pure, unit tested. */
final class ArgumentMapper {
    static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    static final Pattern WORD = Pattern.compile("[A-Za-z0-9_.:+\\-]{1,64}");
    static final Pattern INTEGER = Pattern.compile("-?\\d{1,10}");
    static final Pattern NUMBER = Pattern.compile("-?\\d{1,12}(\\.\\d{1,6})?");
    static final int MAX_TEXT = 256;

    private ArgumentMapper() {}

    /** Thrown for a value that does not fit its argument type. */
    static final class InvalidArgumentException extends Exception {
        private static final long serialVersionUID = 1L;
        final String argument;

        InvalidArgumentException(String argument) {
            super("invalid value for argument " + argument);
            this.argument = argument;
        }
    }

    /**
     * {@code values} are the given arguments in order (at least the required ones). Produces {@code arg1..argN}
     * (all defined arguments; missing optional ones are empty), {@code <name>} and {@code args}.
     */
    static Map<String, String> map(AliasSpec spec, List<String> values) throws InvalidArgumentException {
        List<AliasSpec.ArgSpec> args = spec.arguments();
        if (values.size() < spec.requiredCount() || values.size() > args.size()) {
            throw new InvalidArgumentException(values.size() < spec.requiredCount()
                    ? args.get(values.size()).name() : "(too many)");
        }
        Map<String, String> vars = new HashMap<>();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            AliasSpec.ArgSpec arg = args.get(i);
            String value = i < values.size() ? values.get(i) : "";
            if (i < values.size() && !valid(arg.type(), value)) {
                throw new InvalidArgumentException(arg.name());
            }
            vars.put("arg" + (i + 1), value);
            vars.put(arg.name(), value);
            if (!value.isEmpty()) {
                if (!all.isEmpty()) {
                    all.append(' ');
                }
                all.append(value);
            }
        }
        vars.put("args", all.toString());
        return vars;
    }

    static boolean valid(AliasSpec.ArgType type, String value) {
        return switch (type) {
            case PLAYER -> PLAYER.matcher(value).matches();
            case WORD -> WORD.matcher(value).matches();
            case INTEGER -> INTEGER.matcher(value).matches();
            case NUMBER -> NUMBER.matcher(value).matches();
            case TEXT -> !value.isBlank() && value.length() <= MAX_TEXT && value.chars().noneMatch(Character::isISOControl);
        };
    }
}
