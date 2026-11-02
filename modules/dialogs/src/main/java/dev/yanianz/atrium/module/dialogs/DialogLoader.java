package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Loads every {@code *.yml} in the dialogs folder, one dialog per file (file name = dialog name). Each file is
 * handled on its own: a broken, unreadable or vanished file is reported and skipped, the others still load.
 * Blocking file I/O: call from {@code enable} or an I/O thread.
 *
 * <p>Loading happens in two passes so {@code extends} can be resolved before parsing: first every readable file
 * becomes a plain YAML tree ({@link DialogInheritance}), then each tree is completed with the values of its
 * parents and parsed. A file can therefore extend a dialog that comes later alphabetically.
 */
final class DialogLoader {
    private DialogLoader() {}

    /**
     * @param dialogs  valid dialogs by name, sorted by name
     * @param errors   one line per problem of a skipped file ({@code "<file>: <problem>"})
     * @param warnings problems that did not prevent loading (for example a button opening a missing dialog)
     */
    record Result(Map<String, DialogDefinition> dialogs, List<String> errors, List<String> warnings) {
        Result {
            dialogs = Collections.unmodifiableMap(new LinkedHashMap<>(dialogs));
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        int failedFiles() {
            return (int) errors.stream().map(line -> line.substring(0, Math.max(0, line.indexOf(':')))).distinct()
                    .count();
        }
    }

    /**
     * @param actionCheck syntax check for action lines (the action service's {@code validate}); {@code null} when
     *                    the action service is not available
     */
    static Result load(Path folder, DialogParser.Defaults defaults,
            @Nullable Function<List<String>, List<String>> actionCheck) throws IOException {
        Files.createDirectories(folder);
        List<Path> files;
        try (Stream<Path> stream = Files.list(folder)) {
            files = stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
        Map<String, Map<String, Object>> trees = new LinkedHashMap<>();
        Map<String, String> fileOf = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            try {
                if (!Files.isRegularFile(file)) {
                    errors.add(fileName + ": not a regular file");
                    continue;
                }
                if (!fileName.equals(id + ".yml")) {
                    errors.add(fileName + ": file name must be lower case (rename it to " + id + ".yml)");
                    continue;
                }
                if (trees.containsKey(id)) {
                    errors.add(fileName + ": another file already defines dialog '" + id + "'");
                    continue;
                }
                ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
                if (!root.isMap()) {
                    errors.add(fileName + ": the file must contain a YAML mapping (title, body, inputs, buttons, ...)");
                    continue;
                }
                Map<String, Object> tree = tree(root);
                if (tree == null) {
                    errors.add(fileName + ": the file must contain a YAML mapping (title, body, inputs, buttons, ...)");
                    continue;
                }
                trees.put(id, tree);
                fileOf.put(id, fileName);
            } catch (ConfigurateException e) {
                errors.add(fileName + ": cannot read YAML: " + yamlProblem(e));
            } catch (RuntimeException e) {
                // A file that vanished or a parser bug must not stop the other files from loading.
                errors.add(fileName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        Map<String, DialogDefinition> dialogs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : trees.entrySet()) {
            String id = entry.getKey();
            String fileName = fileOf.get(id);
            try {
                Map<String, Object> resolved = DialogInheritance.resolve(id, trees);
                DialogDefinition definition = DialogParser.parse(id, toNode(resolved), defaults);
                List<String> actionProblems = checkActions(definition, actionCheck);
                if (!actionProblems.isEmpty()) {
                    actionProblems.forEach(problem -> errors.add(fileName + ": " + problem));
                    continue;
                }
                dialogs.put(definition.id(), definition);
            } catch (DialogSyntaxException e) {
                e.problems().forEach(problem -> errors.add(fileName + ": " + problem));
            } catch (RuntimeException e) {
                errors.add(fileName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        for (DialogDefinition dialog : dialogs.values()) {
            for (ButtonSpec button : buttons(dialog)) {
                if (button.openDialog() != null && !dialogs.containsKey(button.openDialog())) {
                    warnings.add(dialog.id() + ".yml: button opens unknown dialog '" + button.openDialog() + "'");
                }
            }
        }
        Map<String, DialogDefinition> sorted = new LinkedHashMap<>();
        dialogs.keySet().stream().sorted().forEach(key -> sorted.put(key, dialogs.get(key)));
        return new Result(sorted, errors, warnings);
    }

    // ---- raw YAML tree <-> ConfigurationNode ---------------------------------------------------------------

    /** The node as plain {@code Map}/{@code List}/scalars; {@code null} when it is not a mapping. */
    private static @Nullable Map<String, Object> tree(ConfigurationNode node) {
        if (!node.isMap()) {
            return null;
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            tree.put(String.valueOf(entry.getKey()), value(entry.getValue()));
        }
        return tree;
    }

    private static @Nullable Object value(ConfigurationNode node) {
        if (node.isMap()) {
            return tree(node);
        }
        if (node.isList()) {
            List<Object> list = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                list.add(value(child));
            }
            return list;
        }
        return node.raw();
    }

    /** Rebuilds a node from the merged tree so {@link DialogParser} reads it exactly like a file. */
    private static ConfigurationNode toNode(Map<String, Object> tree) {
        ConfigurationNode node = YamlConfigurationLoader.builder().build().createNode();
        fill(node, tree);
        return node;
    }

    private static void fill(ConfigurationNode node, @Nullable Object value) {
        try {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    fill(node.node(String.valueOf(entry.getKey())), entry.getValue());
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    fill(node.appendListNode(), item);
                }
            } else if (value != null) {
                node.set(value);
            }
        } catch (SerializationException e) {
            throw new IllegalStateException("cannot rebuild a dialog value", e);
        }
    }

    private static String yamlProblem(ConfigurateException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage()).replaceAll("\\s+", " ").trim();
    }

    // ---- checks -------------------------------------------------------------------------------------------

    private static List<String> checkActions(DialogDefinition dialog,
            @Nullable Function<List<String>, List<String>> actionCheck) {
        List<String> problems = new ArrayList<>();
        if (actionCheck == null) {
            return problems;
        }
        if (!dialog.onOpen().isEmpty()) {
            for (String problem : actionCheck.apply(dialog.onOpen())) {
                problems.add("on-open: " + problem);
            }
        }
        List<ButtonSpec> buttons = buttons(dialog);
        for (int i = 0; i < buttons.size(); i++) {
            ButtonSpec button = buttons.get(i);
            if (button.actions().isEmpty()) {
                continue;
            }
            String where = button == dialog.exit() ? "exit" : "buttons[" + i + "]";
            for (String problem : actionCheck.apply(button.actions())) {
                problems.add(where + ".actions: " + problem);
            }
        }
        return problems;
    }

    static List<ButtonSpec> buttons(DialogDefinition dialog) {
        List<ButtonSpec> all = new ArrayList<>(dialog.buttons());
        if (dialog.exit() != null) {
            all.add(dialog.exit());
        }
        return all;
    }
}
