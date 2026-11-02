package dev.yanianz.atrium.module.recipes;

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
 * Loads every {@code *.yml} in the recipes folder, one recipe per file (the file name is the id). Each file
 * is handled on its own: a broken file is reported and skipped while the other files still load.
 *
 * <p>Blocking file I/O: call from {@code enable}, an I/O thread or the module reload, never a region thread.
 */
public final class RecipeLoader {
    private RecipeLoader() {}

    /**
     * @param recipes valid recipes by id, sorted by id
     * @param errors  one {@code "<file>: <problem>"} line per skipped file
     */
    public record Result(Map<String, RecipeSpec> recipes, List<String> errors) {
        public Result {
            recipes = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(recipes)));
            errors = List.copyOf(errors);
        }

        public static Result empty() {
            return new Result(Map.of(), List.of());
        }
    }

    public static Result load(Path folder) throws IOException {
        Files.createDirectories(folder);
        List<Path> files;
        try (Stream<Path> stream = Files.list(folder)) {
            files = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
        Map<String, RecipeSpec> recipes = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - 4);
            try {
                if (!Files.isRegularFile(file)) {
                    errors.add(fileName + ": not a regular file");
                    continue;
                }
                ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
                recipes.put(id, RecipeParser.parse(id, root));
            } catch (RecipeFormatException e) {
                errors.add(fileName + ": " + e.getMessage());
            } catch (ConfigurateException e) {
                errors.add(fileName + ": cannot read YAML: " + deepestMessage(e));
            } catch (RuntimeException e) {
                // A vanished file or a parser bug must not stop the other recipes from loading.
                errors.add(fileName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return new Result(recipes, errors);
    }

    private static String deepestMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage()).replaceAll("\\s+", " ").trim();
    }
}
