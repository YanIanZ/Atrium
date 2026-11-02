package net.milkbowl.vault;

import dev.yanianz.atrium.vault.AuditLog;
import dev.yanianz.atrium.vault.CallerResolver;
import dev.yanianz.atrium.vault.GuardSettings;
import dev.yanianz.atrium.vault.GuardedEconomy;
import dev.yanianz.atrium.vault.RateLimitedLog;
import dev.yanianz.atrium.vault.ServiceListener;
import dev.yanianz.atrium.vault.StackCallerResolver;
import dev.yanianz.atrium.vault.SuperPermsPermission;
import dev.yanianz.atrium.vault.VaultCommands;
import dev.yanianz.atrium.vault.VaultInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Atrium Vault: plugin main class, at the class name other plugins expect from Vault.
 *
 * <p>This class is original Atrium code. Only the VaultAPI interfaces in {@code net.milkbowl.vault.economy},
 * {@code .permission} and {@code .chat} are the unmodified LGPL-3.0 classes from MilkBowl's VaultAPI.
 *
 * <p>On enable it registers a SuperPerms {@link Permission} fallback at {@link ServicePriority#Lowest} and, unless
 * {@code guard.enabled} is false, a {@link GuardedEconomy} at {@link ServicePriority#Highest} that forwards to the best
 * real economy provider. There are no hooks for legacy plugins: modern providers register themselves.
 */
public final class Vault extends JavaPlugin {
    private volatile GuardedEconomy guard;
    private volatile AuditLog audit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        ServicesManager services = getServer().getServicesManager();
        String apiVersion = apiVersion();

        services.register(Permission.class, new SuperPermsPermission(this, SuperPermsPermission.OnlinePlayers.of(getServer())),
                this, ServicePriority.Lowest);

        if (config.getBoolean("guard.enabled", true)) {
            GuardSettings settings;
            try {
                settings = new GuardSettings(config.getDouble("guard.max-amount", GuardSettings.DEFAULT_MAX_AMOUNT));
            } catch (IllegalArgumentException e) {
                getLogger().warning(e.getMessage() + "; using " + GuardSettings.DEFAULT_MAX_AMOUNT);
                settings = GuardSettings.defaults();
            }
            RateLimitedLog log = new RateLimitedLog(getLogger(),
                    Math.max(1, config.getLong("guard.error-log-interval-seconds", 30)) * 1000L);
            if (config.getBoolean("audit.enabled", true)) {
                audit = new AuditLog(getDataFolder().toPath().resolve("audit"),
                        config.getInt("audit.queue-capacity", 10_000),
                        Math.max(1, config.getLong("audit.max-file-size-mb", 10)) * 1024L * 1024L,
                        config.getInt("audit.max-files", 10), log);
                audit.start();
            }
            CallerResolver callers = config.getBoolean("audit.caller-detection", true)
                    ? new StackCallerResolver(getClass().getClassLoader()) : CallerResolver.NONE;
            guard = new GuardedEconomy(() -> VaultInfo.registrations(services, Economy.class).stream()
                    .map(RegisteredServiceProvider::getProvider).toList(), settings, log, audit, callers);
            guard.slowCallThresholdMillis(config.getLong("guard.slow-call-warn-millis", 50L));
            services.register(Economy.class, guard, this, ServicePriority.Highest);
        }
        getServer().getPluginManager().registerEvents(new ServiceListener(guard, getLogger()), this);

        VaultCommands commands = new VaultCommands(this, apiVersion, () -> guard, () -> audit);
        for (String name : new String[] {"vault-info", "vault-convert"}) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), name + " missing from plugin.yml");
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        getLogger().info("Atrium Vault " + getPluginMeta().getVersion() + " (VaultAPI " + apiVersion + ") enabled; guard "
                + (guard != null ? "on (max " + guard.settings().maxAmount() + ", audit " + (audit != null ? "on" : "off") + ")"
                : "off") + ", SuperPerms fallback registered");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        AuditLog log = audit;
        audit = null;
        if (log != null) log.close();
        guard = null;
    }

    /** VaultAPI version shaded into this jar (written by the build). */
    private String apiVersion() {
        try (InputStream in = getResource("atrium-vault.properties")) {
            if (in == null) return "unknown";
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("vault-api-version", "unknown");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Cannot read atrium-vault.properties", e);
            return "unknown";
        }
    }
}
