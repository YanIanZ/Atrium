package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.BodyLine;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.BoolInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.Option;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import dev.yanianz.atrium.core.util.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Turns one dialog YAML document into a {@link DialogDefinition}. Pure: no server access. Every value is type
 * checked (a wrong type is an error, never a {@link ClassCastException}) and unknown keys are rejected so a typo
 * such as {@code permision:} cannot silently remove a permission. All problems of a file are collected and
 * reported together.
 */
final class DialogParser {
    static final Pattern DIALOG_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Same alphabet as {@code ActionService} variables. */
    static final Pattern INPUT_KEY = Pattern.compile("[a-z0-9_]{1,32}");
    static final Pattern OPTION_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    /** Variables the action service provides itself; inputs may not shadow them. */
    static final Set<String> BUILT_IN_VARIABLES = Set.of("player", "uuid", "world", "x", "y", "z");
    static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\{([a-z0-9_]{1,32})}");
    static final int MAX_WIDTH = 1024;
    static final int MAX_TEXT_LENGTH = 1024;
    static final int MAX_INPUTS = 32;
    static final int MAX_BUTTONS = 32;
    static final int MAX_OPTIONS = 64;
    /** Longest accepted {@code cooldown}. */
    static final Duration MAX_COOLDOWN = Duration.ofDays(30);

    private static final Set<String> ROOT_KEYS = Set.of("extends", "title", "external-title", "permission",
            "conditions", "can-close-with-escape", "columns", "body", "inputs", "buttons", "exit", "on-open",
            "cooldown");
    private static final Set<String> BODY_KEYS = Set.of("text", "width");
    private static final Set<String> BUTTON_KEYS = Set.of("label", "tooltip", "width", "permission", "conditions",
            "actions", "open");
    private static final Set<String> TEXT_KEYS = Set.of("type", "key", "label", "width", "label-visible", "initial",
            "max-length", "multiline-lines", "multiline-height", "pattern");
    private static final Set<String> OPTION_INPUT_KEYS = Set.of("type", "key", "label", "width", "label-visible",
            "options");
    private static final Set<String> OPTION_KEYS = Set.of("id", "label", "initial");
    private static final Set<String> BOOL_KEYS = Set.of("type", "key", "label", "initial", "on-true", "on-false");
    private static final Set<String> NUMBER_KEYS = Set.of("type", "key", "label", "width", "start", "end", "step",
            "initial", "label-format");

    /** Server-wide defaults from {@code dialogs.yml}. */
    record Defaults(Pattern textPattern, int textMaxLength) {}

    private final List<String> errors = new ArrayList<>();
    private final Defaults defaults;

    private DialogParser(Defaults defaults) {
        this.defaults = defaults;
    }

    /** Parses a dialog; throws with every problem found when the file is not valid. */
    static DialogDefinition parse(String id, ConfigurationNode root, Defaults defaults) throws DialogSyntaxException {
        DialogParser parser = new DialogParser(defaults);
        DialogDefinition definition = parser.root(id, root);
        if (!parser.errors.isEmpty()) {
            throw new DialogSyntaxException(parser.errors);
        }
        return definition;
    }

    /** {@code {name}} references in action lines that are neither inputs nor built-ins. */
    static List<String> unknownVariables(List<String> lines, Set<String> inputKeys) {
        List<String> unknown = new ArrayList<>();
        for (String line : lines) {
            Matcher m = VARIABLE_REFERENCE.matcher(line);
            while (m.find()) {
                String name = m.group(1);
                if (!inputKeys.contains(name) && !BUILT_IN_VARIABLES.contains(name) && !unknown.contains(name)) {
                    unknown.add(name);
                }
            }
        }
        return unknown;
    }

    private DialogDefinition root(String id, ConfigurationNode root) {
        if (!DIALOG_ID.matcher(id).matches()) {
            errors.add("file name must be " + DIALOG_ID.pattern() + ".yml (got '" + id + "')");
        }
        if (!root.isMap()) {
            errors.add("the file must contain a YAML mapping (title, body, inputs, buttons, ...)");
            return null;
        }
        keys(root, "", ROOT_KEYS);
        String title = string(root.node("title"), "title", null);
        if (title == null || title.isBlank()) {
            errors.add("title: required");
            title = id;
        }
        String externalTitle = string(root.node("external-title"), "external-title", "");
        String permission = permissionNode(root.node("permission"), "permission");
        List<Condition> conditions = conditions(root.node("conditions"), "conditions");
        boolean escape = bool(root.node("can-close-with-escape"), "can-close-with-escape", true);
        int columns = integer(root.node("columns"), "columns", 2, 1, 16);
        List<BodyLine> body = body(root.node("body"));
        List<InputSpec> inputs = inputs(root.node("inputs"));
        Set<String> keys = new HashSet<>();
        inputs.forEach(input -> keys.add(input.key()));
        List<ButtonSpec> buttons = new ArrayList<>();
        ConfigurationNode buttonsNode = root.node("buttons");
        if (!buttonsNode.virtual()) {
            if (!buttonsNode.isList()) {
                errors.add("buttons: must be a list");
            } else {
                List<? extends ConfigurationNode> list = buttonsNode.childrenList();
                if (list.size() > MAX_BUTTONS) {
                    errors.add("buttons: at most " + MAX_BUTTONS + " buttons");
                }
                for (int i = 0; i < list.size(); i++) {
                    ButtonSpec button = button(list.get(i), "buttons[" + i + "]", keys);
                    if (button != null) {
                        buttons.add(button);
                    }
                }
            }
        }
        ButtonSpec exit = null;
        ConfigurationNode exitNode = root.node("exit");
        if (!exitNode.virtual()) {
            exit = button(exitNode, "exit", keys);
        }
        List<String> onOpen = stringList(root.node("on-open"), "on-open");
        for (String unknown : unknownVariables(onOpen, Set.of())) {
            errors.add("on-open: unknown variable {" + unknown + "} (no input exists when the dialog opens)");
        }
        Duration cooldown = cooldown(root.node("cooldown"));
        return new DialogDefinition(id, title, externalTitle, permission, conditions, escape, columns, body, inputs,
                buttons, exit, onOpen, cooldown);
    }

    /** {@code cooldown: 30s} (or a bare number of seconds); absent/0 means no cooldown. */
    private @Nullable Duration cooldown(ConfigurationNode node) {
        if (node.virtual() || node.raw() == null) {
            return null;
        }
        String text = isScalar(node) ? String.valueOf(node.raw()).trim() : "";
        Optional<Duration> parsed = Durations.parse(text);
        if (parsed.isEmpty()) {
            errors.add("cooldown: must be a duration such as 30s, 5m or 1h30m (got '" + text + "')");
            return null;
        }
        Duration value = parsed.get();
        if (value.isZero() || value.isNegative()) {
            return null;
        }
        if (value.compareTo(MAX_COOLDOWN) > 0) {
            errors.add("cooldown: at most " + Durations.format(MAX_COOLDOWN, 2));
            return null;
        }
        return value;
    }

    private List<BodyLine> body(ConfigurationNode node) {
        List<BodyLine> lines = new ArrayList<>();
        if (node.virtual()) {
            return lines;
        }
        if (isScalar(node)) {
            lines.add(new BodyLine(String.valueOf(node.raw()), 200));
            return lines;
        }
        if (!node.isList()) {
            errors.add("body: must be a text or a list of texts");
            return lines;
        }
        List<? extends ConfigurationNode> list = node.childrenList();
        for (int i = 0; i < list.size(); i++) {
            ConfigurationNode entry = list.get(i);
            String path = "body[" + i + "]";
            if (isScalar(entry)) {
                lines.add(new BodyLine(String.valueOf(entry.raw()), 200));
            } else if (entry.isMap()) {
                keys(entry, path, BODY_KEYS);
                String text = string(entry.node("text"), path + ".text", null);
                if (text == null) {
                    errors.add(path + ".text: required");
                    continue;
                }
                lines.add(new BodyLine(text, integer(entry.node("width"), path + ".width", 200, 1, MAX_WIDTH)));
            } else {
                errors.add(path + ": must be a text or a mapping with text/width");
            }
        }
        return lines;
    }

    private List<InputSpec> inputs(ConfigurationNode node) {
        List<InputSpec> inputs = new ArrayList<>();
        if (node.virtual()) {
            return inputs;
        }
        if (!node.isList()) {
            errors.add("inputs: must be a list");
            return inputs;
        }
        List<? extends ConfigurationNode> list = node.childrenList();
        if (list.size() > MAX_INPUTS) {
            errors.add("inputs: at most " + MAX_INPUTS + " inputs");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            ConfigurationNode entry = list.get(i);
            String path = "inputs[" + i + "]";
            if (!entry.isMap()) {
                errors.add(path + ": must be a mapping");
                continue;
            }
            String key = string(entry.node("key"), path + ".key", null);
            if (key == null || !INPUT_KEY.matcher(key).matches()) {
                errors.add(path + ".key: required, must match " + INPUT_KEY.pattern());
                continue;
            }
            if (BUILT_IN_VARIABLES.contains(key)) {
                errors.add(path + ".key: '" + key + "' is a built-in variable; choose another key");
                continue;
            }
            if (!seen.add(key)) {
                errors.add(path + ".key: duplicate key '" + key + "'");
                continue;
            }
            String type = string(entry.node("type"), path + ".type", "text").toLowerCase(Locale.ROOT);
            String label = string(entry.node("label"), path + ".label", key);
            InputSpec input = switch (type) {
                case "text" -> text(entry, path, key, label);
                case "option", "options", "single-option" -> option(entry, path, key, label);
                case "bool", "boolean", "checkbox" -> bool(entry, path, key, label);
                case "number", "slider", "number-range" -> number(entry, path, key, label);
                default -> {
                    errors.add(path + ".type: unknown type '" + type + "' (text, option, bool, number)");
                    yield null;
                }
            };
            if (input != null) {
                inputs.add(input);
            }
        }
        return inputs;
    }

    private InputSpec text(ConfigurationNode node, String path, String key, String label) {
        keys(node, path, TEXT_KEYS);
        int width = integer(node.node("width"), path + ".width", 200, 1, MAX_WIDTH);
        boolean labelVisible = bool(node.node("label-visible"), path + ".label-visible", true);
        int maxLength = integer(node.node("max-length"), path + ".max-length", defaults.textMaxLength(), 1,
                MAX_TEXT_LENGTH);
        String initial = string(node.node("initial"), path + ".initial", "");
        int lines = integer(node.node("multiline-lines"), path + ".multiline-lines", 0, 0, 100);
        int height = integer(node.node("multiline-height"), path + ".multiline-height", 0, 0, 512);
        Pattern pattern = defaults.textPattern();
        String patternText = string(node.node("pattern"), path + ".pattern", null);
        if (patternText != null) {
            try {
                pattern = Pattern.compile(patternText);
            } catch (PatternSyntaxException e) {
                errors.add(path + ".pattern: invalid regular expression: " + e.getDescription());
                return null;
            }
        }
        if (initial.length() > maxLength) {
            errors.add(path + ".initial: longer than max-length " + maxLength);
        } else if (!initial.isEmpty() && !pattern.matcher(initial).matches()) {
            errors.add(path + ".initial: does not match the input pattern " + pattern.pattern());
        }
        return new TextInput(key, label, width, labelVisible, initial, maxLength, lines, height, pattern);
    }

    private InputSpec option(ConfigurationNode node, String path, String key, String label) {
        keys(node, path, OPTION_INPUT_KEYS);
        int width = integer(node.node("width"), path + ".width", 200, 1, MAX_WIDTH);
        boolean labelVisible = bool(node.node("label-visible"), path + ".label-visible", true);
        ConfigurationNode optionsNode = node.node("options");
        if (!optionsNode.isList() || optionsNode.childrenList().isEmpty()) {
            errors.add(path + ".options: required, a non-empty list");
            return null;
        }
        List<? extends ConfigurationNode> list = optionsNode.childrenList();
        if (list.size() > MAX_OPTIONS) {
            errors.add(path + ".options: at most " + MAX_OPTIONS + " options");
        }
        List<Option> options = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int initials = 0;
        for (int i = 0; i < list.size(); i++) {
            ConfigurationNode entry = list.get(i);
            String optionPath = path + ".options[" + i + "]";
            String id;
            String optionLabel;
            boolean initial = false;
            if (isScalar(entry)) {
                id = String.valueOf(entry.raw());
                optionLabel = id;
            } else if (entry.isMap()) {
                keys(entry, optionPath, OPTION_KEYS);
                id = string(entry.node("id"), optionPath + ".id", null);
                optionLabel = string(entry.node("label"), optionPath + ".label", id);
                initial = bool(entry.node("initial"), optionPath + ".initial", false);
            } else {
                errors.add(optionPath + ": must be an id or a mapping with id/label/initial");
                continue;
            }
            if (id == null || !OPTION_ID.matcher(id).matches()) {
                errors.add(optionPath + ".id: required, must match " + OPTION_ID.pattern());
                continue;
            }
            if (!ids.add(id)) {
                errors.add(optionPath + ".id: duplicate option id '" + id + "'");
                continue;
            }
            if (initial) {
                initials++;
            }
            options.add(new Option(id, optionLabel == null ? id : optionLabel, initial));
        }
        if (initials > 1) {
            errors.add(path + ".options: at most one option may be initial");
        }
        return new OptionInput(key, label, width, labelVisible, options);
    }

    private InputSpec bool(ConfigurationNode node, String path, String key, String label) {
        keys(node, path, BOOL_KEYS);
        boolean initial = bool(node.node("initial"), path + ".initial", false);
        String onTrue = string(node.node("on-true"), path + ".on-true", "true");
        String onFalse = string(node.node("on-false"), path + ".on-false", "false");
        return new BoolInput(key, label, initial, onTrue, onFalse);
    }

    private InputSpec number(ConfigurationNode node, String path, String key, String label) {
        keys(node, path, NUMBER_KEYS);
        int width = integer(node.node("width"), path + ".width", 200, 1, MAX_WIDTH);
        Float start = number(node.node("start"), path + ".start");
        Float end = number(node.node("end"), path + ".end");
        if (start == null || end == null) {
            errors.add(path + ": start and end are required numbers");
            return null;
        }
        if (!(start < end)) {
            errors.add(path + ": start must be lower than end");
            return null;
        }
        Float step = number(node.node("step"), path + ".step");
        if (step != null && !(step > 0)) {
            errors.add(path + ".step: must be positive");
            step = null;
        }
        Float initial = number(node.node("initial"), path + ".initial");
        if (initial != null && (initial < start || initial > end)) {
            errors.add(path + ".initial: must be between start and end");
            initial = null;
        }
        String format = string(node.node("label-format"), path + ".label-format", null);
        return new NumberInput(key, label, width, start, end, step, initial, format);
    }

    private ButtonSpec button(ConfigurationNode node, String path, Set<String> inputKeys) {
        if (!node.isMap()) {
            errors.add(path + ": must be a mapping with label/actions");
            return null;
        }
        keys(node, path, BUTTON_KEYS);
        String label = string(node.node("label"), path + ".label", null);
        if (label == null || label.isBlank()) {
            errors.add(path + ".label: required");
            return null;
        }
        String tooltip = string(node.node("tooltip"), path + ".tooltip", "");
        int width = integer(node.node("width"), path + ".width", 150, 1, MAX_WIDTH);
        String permission = permissionNode(node.node("permission"), path + ".permission");
        List<Condition> conditions = conditions(node.node("conditions"), path + ".conditions");
        List<String> actions = stringList(node.node("actions"), path + ".actions");
        for (String unknown : unknownVariables(actions, inputKeys)) {
            errors.add(path + ".actions: unknown variable {" + unknown + "} (not an input key or built-in)");
        }
        String open = string(node.node("open"), path + ".open", null);
        if (open != null) {
            open = open.toLowerCase(Locale.ROOT);
            if (!DIALOG_ID.matcher(open).matches()) {
                errors.add(path + ".open: not a valid dialog name");
                open = null;
            }
        }
        return new ButtonSpec(label, tooltip, width, permission, actions, open, conditions);
    }

    /** Parses a {@code conditions:} list; every entry is validated here, not at render time. */
    private List<Condition> conditions(ConfigurationNode node, String path) {
        List<Condition> conditions = new ArrayList<>();
        for (String text : stringList(node, path)) {
            try {
                conditions.add(Condition.parse(text));
            } catch (IllegalArgumentException e) {
                errors.add(path + ": " + e.getMessage());
            }
        }
        return conditions;
    }

    // ---- typed readers -------------------------------------------------------------------------------------

    private void keys(ConfigurationNode node, String path, Set<String> allowed) {
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowed.contains(key)) {
                errors.add((path.isEmpty() ? "" : path + ".") + key + ": unknown key (allowed: "
                        + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
    }

    private static boolean isScalar(ConfigurationNode node) {
        Object raw = node.raw();
        return raw instanceof String || raw instanceof Number || raw instanceof Boolean;
    }

    private @Nullable String string(ConfigurationNode node, String path, @Nullable String fallback) {
        if (node.virtual() || node.raw() == null) {
            return fallback;
        }
        if (!isScalar(node)) {
            errors.add(path + ": must be text");
            return fallback;
        }
        return String.valueOf(node.raw());
    }

    private String permissionNode(ConfigurationNode node, String path) {
        String value = string(node, path, "");
        if (value == null) {
            return "";
        }
        value = value.trim();
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9_.*-]{1,128}")) {
            errors.add(path + ": not a valid permission node");
            return "";
        }
        return value;
    }

    private boolean bool(ConfigurationNode node, String path, boolean fallback) {
        if (node.virtual() || node.raw() == null) {
            return fallback;
        }
        Object raw = node.raw();
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        errors.add(path + ": must be true or false");
        return fallback;
    }

    private int integer(ConfigurationNode node, String path, int fallback, int min, int max) {
        if (node.virtual() || node.raw() == null) {
            return fallback;
        }
        Double value = numeric(node.raw());
        if (value == null || value != Math.rint(value)) {
            errors.add(path + ": must be a whole number");
            return fallback;
        }
        if (value < min || value > max) {
            errors.add(path + ": must be between " + min + " and " + max);
            return fallback;
        }
        return value.intValue();
    }

    private @Nullable Float number(ConfigurationNode node, String path) {
        if (node.virtual() || node.raw() == null) {
            return null;
        }
        Double value = numeric(node.raw());
        if (value == null || !Double.isFinite(value) || Math.abs(value) > 1.0e9) {
            errors.add(path + ": must be a finite number");
            return null;
        }
        return value.floatValue();
    }

    private static @Nullable Double numeric(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private List<String> stringList(ConfigurationNode node, String path) {
        List<String> out = new ArrayList<>();
        if (node.virtual() || node.raw() == null) {
            return out;
        }
        if (isScalar(node)) {
            out.add(String.valueOf(node.raw()));
            return out;
        }
        if (!node.isList()) {
            errors.add(path + ": must be a list of lines");
            return out;
        }
        List<? extends ConfigurationNode> list = node.childrenList();
        for (int i = 0; i < list.size(); i++) {
            if (!isScalar(list.get(i))) {
                errors.add(path + "[" + i + "]: must be text");
            } else {
                out.add(String.valueOf(list.get(i).raw()));
            }
        }
        return out;
    }
}
