package dev.yanianz.atrium.core.command;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private volatile Set<String> skippedByConflict = Set.of();
    private volatile boolean preferOtherPluginCommands;

    public CommandService(Logger logger) {
        this.logger = logger;
    }

    public void setDisabled(List<String> names) {
        disabled = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Names (lower case) Atrium skipped because another plugin already registers them. Filled during install. */
    public Set<String> skippedByConflict() {
        return skippedByConflict;
    }

    /**
     * When true, an Atrium command (or alias) that another plugin already registers is not installed, so the
     * other plugin keeps working unchanged. Read once when the command tree is built; a restart is needed.
     */
    public void setPreferOtherPluginCommands(boolean value) {
        this.preferOtherPluginCommands = value;
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
            Map<String, String> owners = otherCommandOwners(plugin);
            List<String> conflicts = new ArrayList<>();
            Set<String> skippedNames = new java.util.HashSet<>();
            int count = 0;
            int skipped = 0;
            for (CommandSpec spec : specs) {
                String name = spec.name().toLowerCase(Locale.ROOT);
                if (disabled.contains(name)) {
                    continue;
                }
                String nameOwner = owners.get(name);
                if (nameOwner != null) {
                    conflicts.add(spec.name());
                    if (preferOtherPluginCommands) {
                        logger.debug("Skipping /{}: {} already registers it", spec.name(), nameOwner);
                        skippedNames.add(name);
                        skipped++;
                        continue;
                    }
                }
                List<String> aliases = spec.aliases().stream()
                        .filter(a -> !disabled.contains(a.toLowerCase(Locale.ROOT)))
                        .filter(a -> {
                            String aliasOwner = owners.get(a.toLowerCase(Locale.ROOT));
                            if (aliasOwner != null) {
                                conflicts.add(a);
                                if (preferOtherPluginCommands) {
                                    logger.debug("Skipping alias /{} of /{}: {} already registers it",
                                            a, spec.name(), aliasOwner);
                                    skippedNames.add(a.toLowerCase(Locale.ROOT));
                                    return false;
                                }
                            }
                            return true;
                        })
                        .toList();
                registrar.register(spec.node(), spec.description(), aliases);
                count++;
            }
            skippedByConflict = Set.copyOf(skippedNames);
            logger.info("Registered {} commands", count);
            if (skipped > 0) {
                logger.info("Skipped {} Atrium commands that another plugin also registers "
                        + "(commands.prefer-other-plugin-commands)", skipped);
            } else if (!conflicts.isEmpty()) {
                logger.info("{} Atrium command names are also registered by other plugins ({}); set "
                                + "commands.prefer-other-plugin-commands: true to keep theirs and skip Atrium's",
                        conflicts.size(), sample(conflicts));
            }
        });
    }

    /** Command name (lower case) -> name of the other plugin that registered it, from the Bukkit command map. */
    private static Map<String, String> otherCommandOwners(JavaPlugin self) {
        Map<String, String> owners = new HashMap<>();
        var map = self.getServer().getCommandMap();
        if (map == null) {
            return owners;
        }
        for (Map.Entry<String, org.bukkit.command.Command> entry : map.getKnownCommands().entrySet()) {
            if (!(entry.getValue() instanceof org.bukkit.command.PluginIdentifiableCommand identifiable)) {
                continue;
            }
            org.bukkit.plugin.Plugin owner = identifiable.getPlugin();
            if (owner != null && owner != self && owner.isEnabled()
                    && self.getServer().getPluginManager().getPlugin(owner.getName()) == owner) {
                owners.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), owner.getName());
            }
        }
        return owners;
    }

    private static String sample(List<String> names) {
        List<String> unique = names.stream().distinct().sorted().limit(8).toList();
        String joined = String.join(", ", unique);
        return names.stream().distinct().count() > unique.size() ? joined + ", ..." : joined;
    }
}

