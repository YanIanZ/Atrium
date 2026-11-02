package dev.yanianz.atrium.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Loads {@code @ConfigSerializable} classes from YAML with Configurate. Missing keys are filled with the
 * defaults declared on the class and written back, so new options appear in old files automatically.
 */
public final class ConfigService {

    public <T> ConfigHandle<T> open(Path path, Class<T> type, Supplier<T> defaults) throws ConfigurateException {
        return new ConfigHandle<>(this, path, type, defaults);
    }

    <T> T loadAndWrite(Path path, Class<T> type, Supplier<T> defaults) throws ConfigurateException {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new ConfigurateException("Cannot create directory for " + path, e);
        }
        YamlConfigurationLoader loader = loader(path);
        CommentedConfigurationNode node = loader.load();
        T value = node.empty() ? defaults.get() : node.get(type);
        if (value == null) {
            value = defaults.get();
        }
        node.set(type, value);
        loader.save(node);
        // configurate-yaml 4.2 does not write comments; add the @Comment texts ourselves.
        YamlComments.apply(path, type);
        return value;
    }

    public static YamlConfigurationLoader loader(Path path) {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(o -> o.shouldCopyDefaults(true))
                .build();
    }

    /** Reads a YAML document from a classpath resource stream. */
    public static CommentedConfigurationNode read(InputStream in) throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
                .build()
                .load();
    }
}
