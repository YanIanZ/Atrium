package dev.yanianz.atrium.core;

import dev.yanianz.atrium.api.Atrium;
import dev.yanianz.atrium.api.AtriumApi;
import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.user.UserService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.config.ConfigService;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.module.ModuleManager;
import dev.yanianz.atrium.core.scheduler.PaperScheduler;
import dev.yanianz.atrium.core.service.SimpleServiceRegistry;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.user.UserManager;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

public final class AtriumPlugin extends JavaPlugin implements AtriumApi {
    private Logger log;
    private ConfigHandle<CoreConfig> config;
    private PaperScheduler scheduler;
    private Messages messages;
    private Database database;
    private UserManager users;
    private dev.yanianz.atrium.core.user.Cooldowns cooldowns;
    private ServiceRegistry services;
    private CommandService commands;
    private ModuleManager modules;
    private dev.yanianz.atrium.core.menu.MenuManager menus;
    private boolean started;
    private final dev.yanianz.atrium.core.permission.Permissions corePermissions =
            new dev.yanianz.atrium.core.permission.Permissions();

    @Override
    public void onEnable() {
        log = getSLF4JLogger();
        try {
            start();
            started = true;
        } catch (Exception e) {
            log.error("Atrium failed to start and will disable itself", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void start() throws Exception {
        ConfigService configs = new ConfigService();
        config = configs.open(getDataPath().resolve("config.yml"), CoreConfig.class, CoreConfig::new);
        CoreConfig core = config.get();

        scheduler = new PaperScheduler(this, log);
        messages = new Messages(getDataPath().resolve("lang"), this::getResource, log);
        messages.configure(core.defaultLocale, core.perPlayerLocale);
        messages.loadBundle("core");
        messages.applyTheme(theme(core));

        database = new Database(core.database, getDataPath(), scheduler.io(), log);
        database.migrate("core", UserManager.MIGRATIONS);
        database.migrate("core-cooldowns", dev.yanianz.atrium.core.user.Cooldowns.MIGRATIONS);

        users = new UserManager(database, scheduler, messages, log, core.offlineCacheMinutes,
                () -> config.get().userLoadTimeoutSeconds);
        getServer().getPluginManager().registerEvents(users, this);
        users.start(core.userSaveIntervalSeconds);
        cooldowns = new dev.yanianz.atrium.core.user.Cooldowns(database);
        users.registerComponent(cooldowns.component());

        menus = new dev.yanianz.atrium.core.menu.MenuManager(this, scheduler, messages, log);
        menus.install();

        services = new SimpleServiceRegistry();
        Cmds cmds = new Cmds(messages);
        commands = new CommandService(log);
        commands.setDisabled(core.disabledCommands);
        corePermissions.register(AtriumCommand.HELP, org.bukkit.permissions.PermissionDefault.TRUE,
                "List the commands you may use with /atrium help");
        corePermissions.register(AtriumCommand.MENU, org.bukkit.permissions.PermissionDefault.TRUE,
                "Browse the commands you may use in a menu with /atrium menu");
        corePermissions.register(AtriumCommand.PROFILE, org.bukkit.permissions.PermissionDefault.TRUE,
                "Show your active profile and the data that follows it with /atrium profile");
        corePermissions.register(AtriumCommand.PROFILE_OTHERS, org.bukkit.permissions.PermissionDefault.OP,
                "Show the active profile of another online player with /atrium profile <player>");
        services.register(dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry.class,
                new dev.yanianz.atrium.core.profile.SimpleProfileScopedDataRegistry());
        commands.register(new AtriumCommand(this, cmds).spec());

        Atrium.install(this);
        modules = new ModuleManager(this, log, scheduler, messages, cmds, commands, configs, database, users,
                services, config::get, cooldowns);
        modules.menus(menus);
        modules.discover();
        modules.enableAll();
        commands.install(this);
        log.info("Atrium {} enabled on {} ({})", getPluginMeta().getVersion(),
                scheduler.isFolia() ? "Folia" : "Paper", getServer().getVersion());
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        if (menus != null) {
            menus.shutdown();
        }
        if (users != null) {
            users.shutdown();
        }
        corePermissions.unregisterAll();
        Atrium.uninstall(this);
        if (scheduler != null) {
            scheduler.close();
        }
        if (database != null) {
            database.close();
        }
    }

    /** Reloads config.yml, every message file and every module. Returns ids of modules that failed. */
    java.util.List<String> reloadEverything() throws Exception {
        CoreConfig core = config.reload();
        messages.configure(core.defaultLocale, core.perPlayerLocale);
        messages.applyTheme(theme(core));
        messages.reloadAll();
        return modules.reloadAll();
    }

    private dev.yanianz.atrium.core.message.Theme theme(CoreConfig core) {
        return dev.yanianz.atrium.core.message.Theme.from(core.theme, problem -> log.warn("config.yml {}", problem));
    }

    dev.yanianz.atrium.core.menu.MenuManager menus() {
        return menus;
    }

    ModuleManager moduleManager() {
        return modules;
    }

    CommandService commandService() {
        return commands;
    }

    PaperScheduler scheduler() {
        return scheduler;
    }

    boolean started() {
        return started;
    }

    @Override
    public UserService users() {
        return users;
    }

    @Override
    public ServiceRegistry services() {
        return services;
    }

    @Override
    public Set<String> enabledModules() {
        return modules == null ? Set.of() : modules.enabledIds();
    }
}
