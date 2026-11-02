package dev.yanianz.atrium.core.config;

import java.nio.file.Path;
import java.util.function.Supplier;
import org.spongepowered.configurate.ConfigurateException;

/** A typed YAML file. {@link #get()} returns the last successfully loaded value. */
public final class ConfigHandle<T> {
    private final ConfigService service;
    private final Path path;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private volatile T value;

    ConfigHandle(ConfigService service, Path path, Class<T> type, Supplier<T> defaults) throws ConfigurateException {
        this.service = service;
        this.path = path;
        this.type = type;
        this.defaults = defaults;
        this.value = service.loadAndWrite(path, type, defaults);
    }

    public T get() {
        return value;
    }

    public Path path() {
        return path;
    }

    /** Re-reads the file. On a syntax error the previous value is kept and the exception is thrown. */
    public T reload() throws ConfigurateException {
        T loaded = service.loadAndWrite(path, type, defaults);
        value = loaded;
        return loaded;
    }
}
