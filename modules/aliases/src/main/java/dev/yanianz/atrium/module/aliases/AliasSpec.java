package dev.yanianz.atrium.module.aliases;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** A validated alias command. Built from {@link AliasesConfig.AliasDefinition}; pure and unit tested. */
record AliasSpec(String name, List<String> aliases, String description, String permission, String permissionDefault,
        boolean playerOnly, String usage, List<ArgSpec> arguments, List<String> actions) {
    static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,32}");
    static final Pattern ARG_NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");
    private static final Set<String> RESERVED = Set.of("player", "uuid", "world", "x", "y", "z", "args");
    private static final Set<String> DEFAULTS = Set.of("true", "false", "op", "not_op");

    enum ArgType { WORD, PLAYER, INTEGER, NUMBER, TEXT }

    record ArgSpec(String name, ArgType type, boolean required, List<String> suggestions) {
        ArgSpec {
            suggestions = List.copyOf(suggestions);
        }
    }

    AliasSpec {
        aliases = List.copyOf(aliases);
        arguments = List.copyOf(arguments);
        actions = List.copyOf(actions);
    }

    int requiredCount() {
        return (int) arguments.stream().filter(ArgSpec::required).count();
    }

    /** Everything that is fixed once the command is registered. */
    String structure() {
        StringBuilder out = new StringBuilder(name).append('|').append(aliases).append('|');
        for (ArgSpec arg : arguments) {
            out.append(arg.name()).append(':').append(arg.type()).append(':').append(arg.required()).append(',');
        }
        return out.toString();
    }

    /** Usage line: the configured one, or {@code /name <required> [optional]}. */
    String usageLine() {
        if (!usage.isBlank()) {
            return usage;
        }
        StringBuilder out = new StringBuilder("/").append(name);
        for (ArgSpec arg : arguments) {
            out.append(' ').append(arg.required() ? "<" : "[").append(arg.name()).append(arg.required() ? ">" : "]");
        }
        return out.toString();
    }

    /**
     * Validates a definition. {@code validateActions} returns action syntax errors. Throws with every problem.
     */
    static AliasSpec from(AliasesConfig.AliasDefinition def, Function<List<String>, List<String>> validateActions) {
        List<String> errors = new ArrayList<>();
        String name = def.name == null ? "" : def.name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            errors.add("invalid name '" + name + "' (lower-case letters, digits, _ and -)");
        }
        List<String> aliases = new ArrayList<>();
        for (String alias : def.aliases == null ? List.<String>of() : def.aliases) {
            String a = alias == null ? "" : alias.strip().toLowerCase(Locale.ROOT);
            if (!NAME.matcher(a).matches()) {
                errors.add("invalid alias '" + a + "'");
            } else if (!a.equals(name) && !aliases.contains(a)) {
                aliases.add(a);
            }
        }
        String permission = def.permission == null || def.permission.isBlank() ? "atrium.alias." + name : def.permission.strip();
        if (!PERMISSION.matcher(permission).matches()) {
            errors.add("invalid permission '" + permission + "'");
        }
        String permissionDefault = def.permissionDefault == null ? "true" : def.permissionDefault.strip().toLowerCase(Locale.ROOT);
        if (!DEFAULTS.contains(permissionDefault)) {
            errors.add("permission-default must be true, false, op or not_op");
        }
        List<ArgSpec> args = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean optionalSeen = false;
        List<AliasesConfig.ArgumentDefinition> defs = def.arguments == null ? List.of() : def.arguments;
        for (int i = 0; i < defs.size(); i++) {
            AliasesConfig.ArgumentDefinition a = defs.get(i);
            String argName = a.name == null ? "" : a.name.strip().toLowerCase(Locale.ROOT);
            if (!ARG_NAME.matcher(argName).matches() || RESERVED.contains(argName) || argName.matches("arg\\d+")) {
                errors.add("invalid argument name '" + argName + "' (reserved: player, uuid, world, x, y, z, args, argN)");
            } else if (!seen.add(argName)) {
                errors.add("duplicate argument '" + argName + "'");
            }
            ArgType type;
            try {
                type = ArgType.valueOf((a.type == null ? "word" : a.type.strip()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("argument '" + argName + "': unknown type '" + a.type + "'");
                type = ArgType.WORD;
            }
            if (type == ArgType.TEXT && i != defs.size() - 1) {
                errors.add("argument '" + argName + "': a text argument must be the last one");
            }
            if (a.required && optionalSeen) {
                errors.add("argument '" + argName + "': required arguments must come before optional ones");
            }
            optionalSeen |= !a.required;
            args.add(new ArgSpec(argName, type, a.required, a.suggestions == null ? List.of() : a.suggestions));
        }
        List<String> actions = def.actions == null ? List.of() : List.copyOf(def.actions);
        if (actions.isEmpty()) {
            errors.add("no actions");
        }
        for (String error : validateActions.apply(actions)) {
            errors.add("actions " + error);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return new AliasSpec(name, aliases, def.description == null ? "" : def.description, permission, permissionDefault,
                def.playerOnly, def.usage == null ? "" : def.usage, args, actions);
    }
}
