package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.config.ConfigService;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;

/**
 * The MOTD override, stored in {@code plugins/Atrium/data/performance/motd.yml}. Atrium owns this file instead
 * of rewriting {@code server.properties}: a plugin must never rewrite the server configuration, and the ping
 * listener applies the value anyway.
 *
 * <p>The value is published in memory only after the write succeeded, so a failed save never shows a value
 * that would be gone after a restart.
 */
final class ServerMOTD {
    record State(boolean enabled, List<String> lines) {
        static final State OFF = new State(false, List.of());

        boolean active() {
            return enabled && !lines.isEmpty();
        }
    }

    private final Path file;
    private final Scheduler scheduler;
    private final Logger logger;
    private final Object lock = new Object();
    private volatile State state = State.OFF;

    ServerMOTD(Path file, Scheduler scheduler, Logger logger) {
        this.file = file;
        this.scheduler = scheduler;
        this.logger = logger;
        load();
    }

    State state() {
        return state;
    }

    /** Blocking, small read. Only called from enable and from the write path (I/O thread). */
    private void load() {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            CommentedConfigurationNode root = ConfigService.loader(file).load();
            boolean enabled = root.node("enabled").getBoolean(false);
            List<String> lines = MotdText.normalize(root.node("lines").getList(String.class, List.of()));
            state = new State(enabled, lines);
        } catch (ConfigurateException e) {
            logger.error("Cannot read {}; the server-list MOTD override stays off", file, e);
        }
    }

    CompletableFuture<Void> setEnabled(boolean enabled) {
        return save(new State(enabled, state.lines()));
    }

    CompletableFuture<Void> setLines(List<String> lines) {
        List<String> normalized = MotdText.normalize(lines);
        // Setting lines switches the override on; an empty list switches it off again.
        return save(new State(!normalized.isEmpty(), normalized));
    }

    private CompletableFuture<Void> save(State next) {
        return scheduler.supplyIo(() -> {
            synchronized (lock) {
                write(next);
                state = next;
                return null;
            }
        });
    }

    private void write(State next) throws IOException {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            YamlConfigurationLoader loader = ConfigService.loader(temp);
            CommentedConfigurationNode root = loader.createNode();
            root.node("enabled").set(next.enabled());
            root.node("lines").set(next.lines());
            loader.save(root);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (ConfigurateException e) {
            throw new IOException("cannot write " + file, e);
        }
    }
}
