package dev.yanianz.atrium.core.command;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

/**
 * Collects commands from modules and registers them through Paper's lifecycle API (the only way on
 * Paper plugins and the one that works on Folia). Registration happens when the server builds its
 * command tree, after every module has enabled.
 */
public final class CommandService {
    /** A registered command and the module that owns it ("core" for the framework itself). */
    public record Entry(String owner, CommandSpec spec) {}

    private final List<CommandSpec> specs = new CopyOnWriteArrayList<>();
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private volatile Set<String> disabled = Set.of();

    public CommandService(Logger logger) {
        this.logger = logger;
    }

    public void setDisabled(List<String> names) {
        disabled = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public void register(CommandSpec spec) {
        register("core", spec);
    }

    public void register(String owner, CommandSpec spec) {
        specs.add(spec);
        entries.add(new Entry(owner, spec));
    }

    public List<CommandSpec> registered() {
        return List.copyOf(specs);
    }

    /** Registered commands with their owners, in registration order. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Names (lower case) disabled through config.yml. */
    public Set<String> disabled() {
        return disabled;
    }

    public void install(JavaPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            int count = 0;
            for (CommandSpec spec : specs) {
                if (disabled.contains(spec.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                List<String> aliases = spec.aliases().stream()
                        .filter(a -> !disabled.contains(a.toLowerCase(Locale.ROOT)))
                        .toList();
                registrar.register(spec.node(), spec.description(), aliases);
                count++;
            }
            logger.info("Registered {} commands", count);
        });
    }
}
