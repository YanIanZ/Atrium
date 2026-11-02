package dev.yanianz.atrium.module.customtext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Loads every {@code *.yml} in the customtext folder, one text per file (file name = text id). Every file is
 * handled on its own: a broken, unreadable or misnamed file is reported and skipped, the others still load.
 *
 * <p>Blocking file I/O: call from {@code enable}, an I/O thread or the module reload.
 */
final class TextLoader {
    static final String RESERVED_ALIAS = "ctext";

    private TextLoader() {}

    /**
     * @param texts  valid texts by id, sorted by id
     * @param aliases command alias to text id, sorted by alias
     * @param errors one line per problem of a skipped file ({@code "<file>: <problem>"})
     * @param warnings problems that did not prevent loading (invalid or duplicate alias, ...)
     */
    record Result(
            Map<String, TextDefinition> texts,
            Map<String, String> aliases,
            List<String> errors,
            List<String> warnings) {
        Result {
            texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
            aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        int failedFiles() {
            return (int) errors.stream()
                    .map(line -> {
                        int colon = line.indexOf(':');
                        return colon < 0 ? line : line.substring(0, colon);
                    })
                    .distinct()
                    .count();
        }

        static Result empty() {
            return new Result(Map.of(), Map.of(), List.of(), List.of());
        }
    }

    static Result load(Path folder) throws IOException {
        return load(folder, TextParser.DEFAULT_AUTO_PAGE_LINES);
    }

    static Result load(Path folder, int autoPageLines) throws IOException {
        Files.createDirectories(folder);
        List<Path> files;
        try (Stream<Path> stream = Files.list(folder)) {
            files = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
        Map<String, TextDefinition> texts = new TreeMap<>();
        Map<String, String> aliases = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - 4);
            if (!TextName.isValid(id)) {
                errors.add(fileName + ": the file name must be " + TextName.RULE);
                continue;
            }
            try {
                if (!Files.isRegularFile(file)) {
                    errors.add(fileName + ": not a regular file");
                    continue;
                }
                ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
                TextParser.Parsed parsed = TextParser.parse(id, root, autoPageLines);
                TextDefinition definition = parsed.definition();
                boolean aliasRegistered = true;
                if (!definition.command().isEmpty()) {
                    String alias = definition.command();
                    if (alias.equals(RESERVED_ALIAS)) {
                        warnings.add(fileName + ": '" + RESERVED_ALIAS
                                + "' is the built-in command; no alias registered");
                        aliasRegistered = false;
                    } else if (aliases.containsKey(alias)) {
                        warnings.add(fileName + ": command '" + alias + "' is already used by "
                                + aliases.get(alias) + ".yml; no alias registered");
                        aliasRegistered = false;
                    } else {
                        aliases.put(alias, id);
                    }
                }
                if (!aliasRegistered) {
                    definition = withoutCommand(definition);
                }
                parsed.warnings().forEach(warning -> warnings.add(fileName + ": " + warning));
                texts.put(id, definition);
            } catch (TextSyntaxException e) {
                errors.add(fileName + ": " + e.getMessage());
            } catch (ConfigurateException e) {
                errors.add(fileName + ": cannot read YAML: " + deepestMessage(e));
            } catch (RuntimeException e) {
                // A vanished file or a parser bug must not stop the other files from loading.
                errors.add(fileName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return new Result(texts, aliases, errors, warnings);
    }

    private static TextDefinition withoutCommand(TextDefinition text) {
        return new TextDefinition(text.name(), "", text.permissions(), text.join(), text.firstJoinOnly(),
                text.cooldownSeconds(), text.hidden(), text.pages());
    }

    private static String deepestMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage()).replaceAll("\\s+", " ").trim();
    }
}
