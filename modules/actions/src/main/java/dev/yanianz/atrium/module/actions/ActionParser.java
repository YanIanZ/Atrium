package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.util.Durations;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;

/**
 * Parser for action lines. Pure: no server access, so everything here is unit tested.
 *
 * <pre>
 * line       := condition* executor?
 * condition  := "[" "!"? name (":" argument)? "]"
 * executor   := name ":" argument  |  "stop"
 * </pre>
 * Conditions are always literal (no variables). Executor arguments may contain {@code {variables}}; arguments
 * without variables are fully validated here, the others after substitution.
 */
final class ActionParser {
    static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.*\\-]{1,128}");
    static final Pattern COOLDOWN_KEY = Pattern.compile("[A-Za-z0-9_.\\-]{1,48}");
    static final Pattern WORLD = Pattern.compile("[A-Za-z0-9_.\\-/]{1,64}");
    static final Pattern WARP = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final Pattern EXECUTOR = Pattern.compile("([A-Za-z][A-Za-z-]*)\\s*:(.*)", Pattern.DOTALL);
    private static final Pattern TICKS = Pattern.compile("(\\d{1,7})\\s*t", Pattern.CASE_INSENSITIVE);
    static final Duration MAX_DELAY = Duration.ofHours(24);
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private ActionParser() {}

    /** Parses all lines; comments ({@code #}) and blank lines are dropped. Throws on the first error. */
    static List<ActionLine> parseAll(List<String> lines) throws ActionSyntaxException {
        List<ActionLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            parse(i + 1, lines.get(i)).ifPresent(out::add);
        }
        return out;
    }

    /** One error per broken line; empty when every line is valid. */
    static List<String> validate(List<String> lines) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            try {
                parse(i + 1, lines.get(i));
            } catch (ActionSyntaxException e) {
                errors.add(e.getMessage());
            }
        }
        return errors;
    }

    /** Empty for blank lines and comments. */
    static Optional<ActionLine> parse(int number, String raw) throws ActionSyntaxException {
        if (raw == null) {
            throw new ActionSyntaxException(number, "empty entry");
        }
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new ActionSyntaxException(number, "a line may not contain line breaks");
        }
        String rest = raw.strip();
        if (rest.isEmpty() || rest.startsWith("#")) {
            return Optional.empty();
        }
        List<Condition> conditions = new ArrayList<>();
        while (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                throw new ActionSyntaxException(number, "unclosed '[' in condition");
            }
            conditions.add(condition(number, rest.substring(1, close)));
            rest = rest.substring(close + 1).strip();
        }
        if (rest.isEmpty()) {
            return Optional.of(new ActionLine(number, conditions, null, "", 0));
        }
        if (rest.equalsIgnoreCase("stop")) {
            return Optional.of(new ActionLine(number, conditions, Executor.STOP, "", 0));
        }
        Matcher m = EXECUTOR.matcher(rest);
        if (!m.matches()) {
            throw new ActionSyntaxException(number, "expected 'executor: argument' but found '" + abbreviate(rest) + "'");
        }
        String name = m.group(1).toLowerCase(Locale.ROOT);
        if (Executor.FORBIDDEN.contains(name)) {
            throw new ActionSyntaxException(number, "'" + name + ":' is not supported: commands never run with operator "
                    + "rights. Use 'console:' for commands that need more permissions.");
        }
        Executor executor = Executor.byName(name);
        if (executor == null) {
            throw new ActionSyntaxException(number, "unknown executor '" + name + ":'");
        }
        String argument = m.group(2).strip();
        long delay = 0;
        switch (executor) {
            case CONSOLE, PLAYER -> {
                while (argument.startsWith("/")) {
                    argument = argument.substring(1);
                }
                argument = argument.strip();
                require(number, !argument.isEmpty(), "missing command");
            }
            case MSG, ACTIONBAR, TITLE -> require(number, !argument.isEmpty(), "missing text");
            case SOUND -> {
                require(number, !argument.isEmpty(), "missing sound key");
                if (!Variables.hasReference(argument)) {
                    sound(number, argument);
                }
            }
            case DELAY -> {
                require(number, !Variables.hasReference(argument), "delay must be a literal duration");
                delay = delayTicks(number, argument);
            }
            case TAKE_MONEY, GIVE_MONEY -> {
                require(number, !argument.isEmpty(), "missing amount");
                if (!Variables.hasReference(argument)) {
                    amount(number, argument);
                }
            }
            case TELEPORT -> {
                require(number, !argument.isEmpty(), "missing location");
                if (!Variables.hasReference(argument)) {
                    location(number, argument);
                }
            }
            case WARP -> {
                require(number, !argument.isEmpty(), "missing warp name");
                if (!Variables.hasReference(argument)) {
                    warp(number, argument);
                }
            }
            case STOP -> {
                // optional message
            }
        }
        return Optional.of(new ActionLine(number, conditions, executor, argument, delay));
    }

    private static Condition condition(int number, String token) throws ActionSyntaxException {
        boolean negated = token.startsWith("!");
        String body = (negated ? token.substring(1) : token).strip();
        int colon = body.indexOf(':');
        String name = (colon < 0 ? body : body.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
        String arg = colon < 0 ? "" : body.substring(colon + 1).strip();
        require(number, !Variables.hasReference(arg), "conditions cannot use variables: [" + token + "]");
        return switch (name) {
            case "perm", "permission" -> {
                require(number, PERMISSION.matcher(arg).matches(), "invalid permission in [" + token + "]");
                yield new Condition.Permission(arg, negated);
            }
            case "money" -> new Condition.Money(amount(number, arg), negated);
            case "world" -> {
                require(number, WORLD.matcher(arg).matches(), "invalid world name in [" + token + "]");
                yield new Condition.World(arg, negated);
            }
            case "cooldown" -> {
                int split = arg.lastIndexOf(':');
                require(number, split > 0, "expected [cooldown:key:duration] but found [" + token + "]");
                String key = arg.substring(0, split).strip();
                require(number, COOLDOWN_KEY.matcher(key).matches(), "invalid cooldown key in [" + token + "]");
                Duration duration = Durations.parse(arg.substring(split + 1))
                        .filter(d -> !d.isZero() && !d.isNegative())
                        .orElseThrow(() -> new ActionSyntaxException(number, "invalid cooldown duration in [" + token + "]"));
                yield new Condition.Cooldown(key, duration, negated);
            }
            case "chance" -> {
                double percent;
                try {
                    percent = Double.parseDouble(arg.endsWith("%") ? arg.substring(0, arg.length() - 1) : arg);
                } catch (NumberFormatException e) {
                    throw new ActionSyntaxException(number, "invalid chance in [" + token + "]");
                }
                require(number, percent > 0 && percent <= 100, "chance must be above 0 and at most 100 in [" + token + "]");
                yield new Condition.Chance(percent, negated);
            }
            default -> throw new ActionSyntaxException(number, "unknown condition [" + token + "]");
        };
    }

    // ---- argument parsers, also used at run time after substitution ----

    static BigDecimal amount(int number, String text) throws ActionSyntaxException {
        BigDecimal amount;
        try {
            amount = new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            throw new ActionSyntaxException(number, "invalid amount '" + abbreviate(text) + "'");
        }
        require(number, amount.signum() > 0, "amount must be positive");
        require(number, amount.compareTo(MAX_AMOUNT) <= 0, "amount is too large");
        require(number, amount.scale() <= 4, "amount has too many decimals");
        return amount;
    }

    static long delayTicks(int number, String text) throws ActionSyntaxException {
        String trimmed = text.strip();
        Matcher ticks = TICKS.matcher(trimmed);
        Duration duration;
        if (ticks.matches()) {
            duration = Duration.ofMillis(Long.parseLong(ticks.group(1)) * 50L);
        } else {
            duration = Durations.parse(trimmed)
                    .orElseThrow(() -> new ActionSyntaxException(number, "invalid delay '" + abbreviate(text) + "' (use 2s, 500ms or 40t)"));
        }
        require(number, duration.compareTo(MAX_DELAY) <= 0, "delay is longer than 24h");
        // Round up: a non-zero delay never collapses to "no delay".
        return (duration.toMillis() + 49) / 50;
    }

    record SoundSpec(Key key, float volume, float pitch) {}

    static SoundSpec sound(int number, String text) throws ActionSyntaxException {
        String[] parts = text.strip().split("\\s+");
        require(number, parts.length <= 3, "expected 'sound: key [volume] [pitch]'");
        Key key;
        try {
            key = Key.key(parts[0].toLowerCase(Locale.ROOT));
        } catch (InvalidKeyException e) {
            throw new ActionSyntaxException(number, "invalid sound key '" + abbreviate(parts[0]) + "'");
        }
        float volume = parts.length > 1 ? floatArg(number, parts[1], 0f, 10f, "volume") : 1f;
        float pitch = parts.length > 2 ? floatArg(number, parts[2], 0f, 2f, "pitch") : 1f;
        return new SoundSpec(key, volume, pitch);
    }

    static StoredLocation location(int number, String text) throws ActionSyntaxException {
        String[] parts = text.strip().split("\\s+");
        require(number, parts.length == 4 || parts.length == 6, "expected 'teleport: world x y z [yaw pitch]'");
        require(number, WORLD.matcher(parts[0]).matches(), "invalid world name '" + abbreviate(parts[0]) + "'");
        double x = doubleArg(number, parts[1]);
        double y = doubleArg(number, parts[2]);
        double z = doubleArg(number, parts[3]);
        float yaw = parts.length == 6 ? floatArg(number, parts[4], -360f, 360f, "yaw") : 0f;
        float pitch = parts.length == 6 ? floatArg(number, parts[5], -90f, 90f, "pitch") : 0f;
        return new StoredLocation(parts[0], x, y, z, yaw, pitch);
    }

    static String warp(int number, String text) throws ActionSyntaxException {
        String name = text.strip();
        require(number, WARP.matcher(name).matches(), "invalid warp name '" + abbreviate(name) + "'");
        return name;
    }

    private static double doubleArg(int number, String text) throws ActionSyntaxException {
        try {
            double value = Double.parseDouble(text);
            require(number, Double.isFinite(value) && Math.abs(value) <= 30_000_000, "coordinate out of range: " + text);
            return value;
        } catch (NumberFormatException e) {
            throw new ActionSyntaxException(number, "invalid number '" + abbreviate(text) + "'");
        }
    }

    private static float floatArg(int number, String text, float min, float max, String what) throws ActionSyntaxException {
        try {
            float value = Float.parseFloat(text);
            require(number, Float.isFinite(value) && value >= min && value <= max,
                    what + " must be between " + min + " and " + max);
            return value;
        } catch (NumberFormatException e) {
            throw new ActionSyntaxException(number, "invalid " + what + " '" + abbreviate(text) + "'");
        }
    }

    private static void require(int number, boolean condition, String message) throws ActionSyntaxException {
        if (!condition) {
            throw new ActionSyntaxException(number, message);
        }
    }

    static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 37) + "...";
    }
}
