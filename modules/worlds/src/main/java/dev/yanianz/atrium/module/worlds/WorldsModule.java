package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldProviderRegistry;
import dev.yanianz.atrium.api.world.WorldResolver;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;

/**
 * World teleport across world managers. Publishes the provider chain ({@link WorldProviderRegistry}) and the
 * {@link WorldResolver} that walks it, so /wtp, homes, warps and /back load known-but-unloaded worlds of any
 * provider. Built-in providers: Bukkit (loaded worlds), Multiverse-Core 5 and Advanced Slime Paper; the
 * {@code sourbycraft} module adds Aurora World Fabric (it enables after this module).
 *
 * <p>Isolation: this class references no other plugin's type. Multiverse and ASP classes are touched only in
 * {@link Hooks} after the plugin or API was found.
 */
public final class WorldsModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<WorldsConfig> config;
    private volatile WorldProviderChain.Settings chainSettings =
            new WorldProviderChain.Settings(List.of(), true, Duration.ofSeconds(60));
    private WorldProviderChain chain;
    private WorldCommands commands;

    @Override
    public String id() {
        return "worlds";
    }

    @Override
    public String description() {
        return "World teleport across Bukkit, Multiverse-Core, Advanced Slime Paper and SourbyCraft AWF worlds (/wtp, /worlds, /worldinfo)";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("teleport");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(WorldsConfig.class, WorldsConfig::new);
        applyConfig();
        this.chain = new WorldProviderChain(() -> chainSettings, Bukkit::getWorld, context.logger());
        chain.register(new BukkitWorldProvider());

        List<String> active = new ArrayList<>(List.of(WorldProvider.BUKKIT));
        WorldsConfig cfg = config.get();
        if (cfg.multiverse.enabled && pluginEnabled(MultiverseWorldProvider.PLUGIN)) {
            if (present(MultiverseWorldProvider.API_CLASS)) {
                register(Hooks.multiverse(context, config), active);
            } else {
                context.logger().warn("Multiverse-Core is enabled but its 5.x API was not found; only Multiverse-Core 5 is supported.");
            }
        }
        if (cfg.slime.enabled && present(SlimeWorldProvider.API_CLASS)) {
            WorldProvider slime = Hooks.slime(context, cfg.slime);
            if (slime != null) {
                register(slime, active);
            }
        }

        context.service(WorldProviderRegistry.class, chain);
        try {
            context.service(WorldResolver.class, chain);
        } catch (IllegalStateException e) {
            context.logger().warn("Another WorldResolver is already registered; homes, warps and /back keep using it, "
                    + "/wtp uses the world provider chain.");
        }

        context.permission(WorldAccess.PERM_WTP, PermissionDefault.OP, "Teleport to a world with /wtp");
        context.permission(WorldAccess.PERM_OTHERS, PermissionDefault.OP, "Teleport another player to a world with /wtp");
        context.permission(WorldAccess.PERM_WORLD_ALL, PermissionDefault.OP,
                "Use /wtp for every world when require-world-permission is on (per world: atrium.wtp.world.<world>)");
        context.permission(WorldAccess.PERM_PROFILE, PermissionDefault.TRUE,
                "Teleport to the primary world of the active SourbyCraft profile with /wtp profile");
        context.permission(WorldCommands.PERM_WORLDS, PermissionDefault.OP, "List worlds with /worlds");
        context.permission(WorldCommands.PERM_LOAD, PermissionDefault.OP, "Load a known world with /worlds load");
        context.permission(WorldCommands.PERM_UNLOAD, PermissionDefault.OP, "Unload an empty Multiverse or slime world with /worlds unload");
        context.permission(WorldCommands.PERM_INFO, PermissionDefault.OP, "Show world details with /worldinfo");

        boolean mvtp = cfg.mvtpAlias && Bukkit.getPluginManager().getPlugin(MultiverseWorldProvider.PLUGIN) == null;
        this.commands = new WorldCommands(context, config, chain);
        commands.register(mvtp);
        context.logger().info("World providers: {} (order: {})", String.join(", ", active),
                chain.providers().stream().map(WorldProvider::id).toList());
    }

    private void register(WorldProvider provider, List<String> active) {
        chain.register(provider);
        active.add(provider.id());
    }

    @Override
    public void reload() {
        applyConfig();
    }

    @Override
    public void disable() {
        if (commands != null) {
            commands.clear();
        }
    }

    private void applyConfig() {
        WorldsConfig cfg = config.get();
        int timeout = Math.max(5, Math.min(300, cfg.loadTimeoutSeconds));
        chainSettings = new WorldProviderChain.Settings(cfg.providerOrder, cfg.loadOnDemand, Duration.ofSeconds(timeout));
    }

    private boolean pluginEnabled(String name) {
        return ctx.plugin().getServer().getPluginManager().isPluginEnabled(name);
    }

    /** Whether a class is visible to Atrium, without initialising it. */
    static boolean present(String className) {
        try {
            Class.forName(className, false, WorldsModule.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
