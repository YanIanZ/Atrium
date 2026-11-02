package net.milkbowl.vault;

import dev.yanianz.atrium.vault.AuditLog;
import dev.yanianz.atrium.vault.BridgeManager;
import dev.yanianz.atrium.vault.DecimalLimits;
import dev.yanianz.atrium.vault.GuardedEconomyV2;
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
import java.math.BigDecimal;
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
 * {@code .permission} and {@code .chat} are the unmodified LGPL-3.0 classes from MilkBowl's VaultAPI, and everything
 * under {@code net.milkbowl.vault2} is the unmodified LGPL-3.0 VaultUnlockedAPI (the Vault v2 API).
 *
 * <p>On enable it registers a SuperPerms {@link Permission} fallback at {@link ServicePriority#Lowest} and, unless
 * {@code guard.enabled} is false, a {@link GuardedEconomy} (v1) and a {@link GuardedEconomyV2} (v2) at
 * {@link ServicePriority#Highest} that forward to the best real provider of their API. Unless {@code bridge.enabled} is
 * false, a {@link BridgeManager} exposes a v1-only economy to v2 plugins and a v2-only economy to v1 plugins. There are
 * no hooks for legacy plugins: modern providers register themselves.
 */
public final class Vault extends JavaPlugin {
    private volatile GuardedEconomy guard;
    private volatile GuardedEconomyV2 guardV2;
    private volatile BridgeManager bridges;
    private volatile AuditLog audit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        ServicesManager services = getServer().getServicesManager();
        Properties versions = versions();
        String apiVersion = versions.getProperty("vault-api-version", "unknown");
        String unlockedVersion = versions.getProperty("vault-unlocked-api-version", "unknown");

        services.register(Permission.class, new SuperPermsPermission(this, SuperPermsPermission.OnlinePlayers.of(getServer())),
                this, ServicePriority.Lowest);

        RateLimitedLog log = new RateLimitedLog(getLogger(),
                Math.max(1, config.getLong("guard.error-log-interval-seconds", 30)) * 1000L);
        CallerResolver callers = config.getBoolean("audit.caller-detection", true)
                ? new StackCallerResolver(getClass().getClassLoader()) : CallerResolver.NONE;
        if (config.getBoolean("guard.enabled", true)) {
            GuardSettings settings;
            try {
                settings = new GuardSettings(config.getDouble("guard.max-amount", GuardSettings.DEFAULT_MAX_AMOUNT));
            } catch (IllegalArgumentException e) {
                getLogger().warning(e.getMessage() + "; using " + GuardSettings.DEFAULT_MAX_AMOUNT);
                settings = GuardSettings.defaults();
            }
            if (config.getBoolean("audit.enabled", true)) {
                audit = new AuditLog(getDataFolder().toPath().resolve("audit"),
                        config.getInt("audit.queue-capacity", 10_000),
                        Math.max(1, config.getLong("audit.max-file-size-mb", 10)) * 1024L * 1024L,
                        config.getInt("audit.max-files", 10), log);
                audit.start();
            }
            guard = new GuardedEconomy(() -> VaultInfo.registrations(services, Economy.class).stream()
                    .map(RegisteredServiceProvider::getProvider).toList(), settings, log, audit, callers);
            guard.slowCallThresholdMillis(config.getLong("guard.slow-call-warn-millis", 50L));
            services.register(Economy.class, guard, this, ServicePriority.Highest);

            DecimalLimits limits;
            try {
                limits = new DecimalLimits(BigDecimal.valueOf(settings.maxAmount()),
                        config.getInt("guard.max-scale", DecimalLimits.DEFAULT_MAX_SCALE),
                        config.getInt("guard.max-precision", DecimalLimits.DEFAULT_MAX_PRECISION));
            } catch (IllegalArgumentException e) {
                getLogger().warning(e.getMessage() + "; using the defaults");
                limits = DecimalLimits.of(settings);
            }
            guardV2 = new GuardedEconomyV2(() -> VaultInfo.registrations(services, net.milkbowl.vault2.economy.Economy.class)
                    .stream().map(RegisteredServiceProvider::getProvider).toList(), limits, log, audit, callers);
            guardV2.slowCallThresholdMillis(config.getLong("guard.slow-call-warn-millis", 50L));
            services.register(net.milkbowl.vault2.economy.Economy.class, guardV2, this, ServicePriority.Highest);
        }
        if (config.getBoolean("bridge.enabled", true)) {
            bridges = new BridgeManager(this, services, getLogger(), log, getServer()::getOfflinePlayer, this::cachedUuid, callers);
        }
        getServer().getPluginManager().registerEvents(new ServiceListener(() -> guard, () -> guardV2, () -> bridges,
                getLogger()), this);
        if (bridges != null) bridges.reconcile();

        VaultCommands commands = new VaultCommands(this, new VaultInfo.Versions(apiVersion, unlockedVersion), () -> guard,
                () -> guardV2, () -> bridges, () -> audit);
        for (String name : new String[] {"vault-info", "vault-convert"}) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), name + " missing from plugin.yml");
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        getLogger().info("Atrium Vault " + getPluginMeta().getVersion() + " (VaultAPI " + apiVersion + ", VaultUnlockedAPI "
                + unlockedVersion + ") enabled; guard v1+v2 " + (guard != null ? "on (max " + guard.settings().maxAmount()
                + ", audit " + (audit != null ? "on" : "off") + ")" : "off") + ", v1/v2 bridge "
                + (bridges != null ? "on" : "off") + ", SuperPerms fallback registered");
    }

    @Override
    public void onDisable() {
        BridgeManager manager = bridges;
        bridges = null;
        if (manager != null) manager.close();
        getServer().getServicesManager().unregisterAll(this);
        AuditLog log = audit;
        audit = null;
        if (log != null) log.close();
        guard = null;
        guardV2 = null;
    }

    /** Legacy name lookups by the v2->v1 bridge: online players, then the profile cache. Never blocks on the network. */
    private java.util.UUID cachedUuid(String name) {
        org.bukkit.entity.Player online = getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        org.bukkit.OfflinePlayer cached = getServer().getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    /** API versions shaded into this jar (written by the build). */
    private Properties versions() {
        Properties properties = new Properties();
        try (InputStream in = getResource("atrium-vault.properties")) {
            if (in != null) properties.load(in);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Cannot read atrium-vault.properties", e);
        }
        return properties;
    }
}
