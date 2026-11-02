package dev.yanianz.atrium.core.module;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandService;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.config.ConfigService;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.permission.Permissions;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import dev.yanianz.atrium.core.user.UserManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Discovers modules, applies {@code modules.yml}, enables them in dependency order. */
public final class ModuleManager {
    public enum State { DISABLED_BY_CONFIG, MISSING_DEPENDENCY, FAILED, ENABLED }

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Scheduler scheduler;
    private final Messages messages;
    private final Cmds cmds;
    private final CommandService commands;
    private final ConfigService configs;
    private final Database database;
    private final UserManager users;
    private final ServiceRegistry services;
    private final Supplier<CoreConfig> coreConfig;
    private final dev.yanianz.atrium.core.user.Cooldowns cooldowns;
    private final Map<String, AtriumModule> modules = new TreeMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Context> contexts = new LinkedHashMap<>();
    private volatile dev.yanianz.atrium.core.menu.@org.jspecify.annotations.Nullable MenuManager menus;

    public ModuleManager(JavaPlugin plugin, Logger logger, Scheduler scheduler, Messages messages, Cmds cmds,
            CommandService commands, ConfigService configs, Database database, UserManager users,
            ServiceRegistry services, Supplier<CoreConfig> coreConfig, dev.yanianz.atrium.core.user.Cooldowns cooldowns) {
        this.cooldowns = cooldowns;
        this.plugin = plugin;
        this.logger = logger;
        this.scheduler = scheduler;
        this.messages = messages;
        this.cmds = cmds;
        this.commands = commands;
        this.configs = configs;
        this.database = database;
        this.users = users;
        this.services = services;
        this.coreConfig = coreConfig;
    }

    /** Installs the menu engine used by {@link ModuleContext#menus()}. Call before {@link #enableAll()}. */
    public void menus(dev.yanianz.atrium.core.menu.MenuManager menus) {
        this.menus = menus;
    }

    public void discover() {
        var providers = ServiceLoader.load(AtriumModule.class, plugin.getClass().getClassLoader()).stream().iterator();
        while (true) {
            ServiceLoader.Provider<AtriumModule> provider;
            AtriumModule module;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                provider = providers.next();
                module = provider.get();
            } catch (java.util.ServiceConfigurationError | LinkageError e) {
                // One broken module (missing class, failing constructor) must not take the others down.
                logger.error("Skipping a module that cannot be loaded", e);
                continue;
            }
            AtriumModule previous = modules.putIfAbsent(module.id(), module);
            if (previous != null) {
                logger.error("Duplicate module id '{}': {} and {}", module.id(), previous.getClass(), module.getClass());
            }
        }
        logger.info("Found {} modules", modules.size());
    }

    /** Reads (and completes) modules.yml: one boolean per module. */
    private Map<String, Boolean> toggles() throws IOException {
        Path file = plugin.getDataPath().resolve("modules.yml");
        YamlConfigurationLoader loader = ConfigService.loader(file);
        CommentedConfigurationNode root = loader.load();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (AtriumModule module : modules.values()) {
            CommentedConfigurationNode node = root.node(module.id());
            if (node.virtual()) {
                node.set(module.enabledByDefault());
                node.comment(module.description().isBlank() ? null : module.description());
            }
            result.put(module.id(), node.getBoolean(module.enabledByDefault()));
        }
        loader.save(root);
        return result;
    }

    /** Reads modules.yml without adding or saving anything: used by a reload, which must not rewrite the file. */
    private Map<String, Boolean> readToggles() throws IOException {
        Path file = plugin.getDataPath().resolve("modules.yml");
        CommentedConfigurationNode root = ConfigService.loader(file).load();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (AtriumModule module : modules.values()) {
            CommentedConfigurationNode node = root.node(module.id());
            result.put(module.id(), node.virtual()
                    ? module.enabledByDefault() : node.getBoolean(module.enabledByDefault()));
        }
        return result;
    }

    public void enableAll() throws IOException {
        Map<String, Boolean> toggles = toggles();
        for (String id : order()) {
            AtriumModule module = modules.get(id);
            if (!toggles.getOrDefault(id, false)) {
                states.put(id, State.DISABLED_BY_CONFIG);
                continue;
            }
            List<String> missing = module.dependencies().stream()
                    .filter(dep -> states.get(dep) != State.ENABLED)
                    .toList();
            if (!missing.isEmpty()) {
                states.put(id, State.MISSING_DEPENDENCY);
                logger.warn("Module {} not enabled: required modules not enabled: {}", id, missing);
                continue;
            }
            Context context = new Context(id);
            contexts.put(id, context);
            try {
                messages.loadBundle(id);
                module.enable(context);
                states.put(id, State.ENABLED);
            } catch (Exception | LinkageError e) {
                states.put(id, State.FAILED);
                logger.error("Module {} failed to enable", id, e);
                safeDisable(module);
                context.cleanup();
                contexts.remove(id);
            }
        }
        long enabled = states.values().stream().filter(s -> s == State.ENABLED).count();
        logger.info("Enabled {}/{} modules", enabled, modules.size());
    }

    /** Topological order honouring hard and soft dependencies; cycles are broken arbitrarily with a warning. */
    private List<String> order() {
        List<String> result = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : modules.keySet()) {
            visit(id, done, visiting, result, new ArrayDeque<>());
        }
        return result;
    }

    private void visit(String id, Set<String> done, Set<String> visiting, List<String> out, Deque<String> path) {
        if (done.contains(id) || !modules.containsKey(id)) {
            return;
        }
        if (!visiting.add(id)) {
            logger.warn("Module dependency cycle: {} -> {}", path, id);
            return;
        }
        path.push(id);
        AtriumModule module = modules.get(id);
        Set<String> deps = new HashSet<>(module.dependencies());
        deps.addAll(module.softDependencies());
        deps.stream().sorted().forEach(dep -> visit(dep, done, visiting, out, path));
        path.pop();
        visiting.remove(id);
        done.add(id);
        out.add(id);
    }

    public void disableAll() {
        List<String> ids = new ArrayList<>(contexts.keySet());
        java.util.Collections.reverse(ids);
        for (String id : ids) {
            safeDisable(modules.get(id));
            contexts.remove(id).cleanup();
            states.put(id, State.DISABLED_BY_CONFIG);
        }
    }

    private void safeDisable(AtriumModule module) {
        try {
            module.disable();
        } catch (Exception | LinkageError e) {
            logger.error("Module {} failed to disable cleanly", module.id(), e);
        }
    }

    /** Reloads module configs through each module's {@link AtriumModule#reload()}. Returns failures. */
    public List<String> reloadAll() {
        List<String> failed = new ArrayList<>();
        reportToggleChanges();
        for (Context context : contexts.values()) {
            AtriumModule module = modules.get(context.id);
            try {
                for (ConfigHandle<?> handle : context.configs) {
                    handle.reload();
                }
                module.reload();
            } catch (Exception e) {
                failed.add(context.id);
                logger.error("Module {} failed to reload", context.id, e);
            }
        }
        return failed;
    }

    /**
     * modules.yml is read when modules are enabled; commands are registered once through Paper's lifecycle
     * event, so a module cannot be started safely by a reload. Disabling is equally unsafe: its listeners and
     * services would stop while its commands stayed in the tree. Tell the admin instead of half-applying it.
     */
    private void reportToggleChanges() {
        Map<String, Boolean> toggles;
        try {
            toggles = readToggles();
        } catch (IOException | RuntimeException e) {
            logger.warn("Cannot read modules.yml while reloading; module toggles were not checked", e);
            return;
        }
        List<String> changed = new ArrayList<>();
        for (AtriumModule module : modules.values()) {
            boolean wanted = toggles.getOrDefault(module.id(), module.enabledByDefault());
            boolean running = contexts.containsKey(module.id());
            if (wanted != running) {
                changed.add(module.id() + (wanted ? " (start)" : " (stop)"));
            }
        }
        if (!changed.isEmpty()) {
            logger.warn("modules.yml changed for {}; module start/stop needs a server restart: {}",
                    changed.size(), String.join(", ", changed));
        }
    }

    public Map<String, State> states() {
        return Map.copyOf(states);
    }

    public Set<String> enabledIds() {
        return contexts.keySet().stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Permission nodes registered by each enabled module, sorted by module id. */
    public java.util.SortedMap<String, java.util.Set<String>> permissionsByModule() {
        java.util.SortedMap<String, java.util.Set<String>> result = new java.util.TreeMap<>();
        contexts.forEach((id, context) -> result.put(id, new java.util.TreeSet<>(context.permissions.nodes())));
        return result;
    }

    public Collection<AtriumModule> modules() {
        return List.copyOf(modules.values());
    }

    private final class Context implements ModuleContext {
        private final String id;
        private final Logger moduleLogger;
        private final List<Listener> listeners = new ArrayList<>();
        private final java.util.Queue<TaskHandle> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final List<Runnable> undo = new ArrayList<>();
        private final List<ConfigHandle<?>> configs = new ArrayList<>();
        private final Permissions permissions = new Permissions();

        Context(String id) {
            this.id = id;
            this.moduleLogger = org.slf4j.LoggerFactory.getLogger(logger.getName() + "/" + id);
        }

        @Override public String moduleId() { return id; }
        @Override public JavaPlugin plugin() { return plugin; }
        @Override public Logger logger() { return moduleLogger; }
        @Override public Scheduler scheduler() { return scheduler; }
        @Override public Messages messages() { return messages; }
        @Override public Cmds commands() { return cmds; }
        @Override public Database database() { return database; }
        @Override public UserManager users() { return users; }
        @Override public ServiceRegistry services() { return services; }
        @Override public CoreConfig coreConfig() { return coreConfig.get(); }
        @Override public Permissions permissions() { return permissions; }
        @Override public dev.yanianz.atrium.core.user.Cooldowns cooldowns() { return cooldowns; }

        @Override
        public dev.yanianz.atrium.core.menu.MenuService menus() {
            var manager = menus;
            return manager != null ? manager.scoped(id) : dev.yanianz.atrium.core.menu.MenuManager.installed();
        }

        @Override
        public Path dataDirectory() {
            Path dir = plugin.getDataPath().resolve("data").resolve(id);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create " + dir, e);
            }
            return dir;
        }

        @Override
        public <T> ConfigHandle<T> config(Class<T> type, Supplier<T> defaults) throws Exception {
            ConfigHandle<T> handle = ModuleManager.this.configs.open(
                    plugin.getDataPath().resolve("modules").resolve(id + ".yml"), type, defaults);
            configs.add(handle);
            return handle;
        }

        @Override
        public void migrate(List<Migration> migrations) throws Exception {
            database.migrate(id, migrations);
        }

        @Override
        public void command(CommandSpec spec) {
            commands.register(id, spec);
        }

        @Override
        public void listen(Listener listener) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            listeners.add(listener);
        }

        @Override
        public TaskHandle track(TaskHandle task) {
            // Thread-safe; finished one-shot tasks are pruned so short-lived timers can be tracked freely.
            tasks.removeIf(TaskHandle::isDone);
            tasks.add(task);
            return task;
        }

        @Override
        public <T> void service(Class<T> type, T implementation) {
            services.register(type, implementation);
            undo.add(() -> services.unregister(type, implementation));
        }

        @Override
        public <T> void component(UserComponentType<T> type) {
            users.registerComponent(type);
            undo.add(() -> users.unregisterComponent(type));
        }

        @Override
        public <T> void codec(UserComponentType<T> type, dev.yanianz.atrium.core.user.ComponentCodec<T> codec) {
            users.registerCodec(type, codec);
            undo.add(() -> users.unregisterCodec(type, codec));
        }

        @Override
        public void profileScoped(dev.yanianz.atrium.api.profile.ProfileScopedData data) {
            var registry = services.get(dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry.class);
            if (registry.isEmpty()) {
                return;
            }
            registry.get().register(data);
            undo.add(() -> registry.get().unregister(data));
        }

        @Override
        public void permission(String node, PermissionDefault def, String description) {
            permissions.register(node, def, description);
        }

        void cleanup() {
            var manager = menus;
            if (manager != null) {
                try {
                    manager.closeOwnedBy(id);
                } catch (RuntimeException e) {
                    logger.warn("Closing the menus of module {} failed", id, e);
                }
            }
            listeners.forEach(HandlerList::unregisterAll);
            tasks.forEach(TaskHandle::cancel);
            for (int i = undo.size() - 1; i >= 0; i--) {
                try {
                    undo.get(i).run();
                } catch (RuntimeException e) {
                    logger.warn("Cleanup step of module {} failed", id, e);
                }
            }
            permissions.unregisterAll();
        }
    }
}
