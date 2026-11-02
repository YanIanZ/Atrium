package dev.yanianz.atrium.module.integrations;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.api.holograms.HologramService;
import dev.yanianz.atrium.api.meta.MetaService;
import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Optional bridges to protection, metadata and display plugins.
 *
 * <p>Hooks are isolated classes: a server without WorldGuard never loads {@link WorldGuardHook}, so the
 * optional APIs cannot cause a {@code NoClassDefFoundError}. Every hook is guarded by
 * {@code isPluginEnabled}; a plugin enabled after Atrium is picked up through {@link PluginEnableEvent}.
 */
public final class IntegrationsModule implements AtriumModule, Listener {
    private static final String PERM_STATUS = "atrium.integrations";
    private static final String PERM_BYPASS = CompositeProtectionService.BYPASS_PERMISSION;

    private static final List<ProtectionSpec> PROTECTION = List.of(
            new ProtectionSpec("worldguard", "WorldGuard"),
            new ProtectionSpec("griefprevention", "GriefPrevention"),
            new ProtectionSpec("lands", "Lands"),
            new ProtectionSpec("towny", "Towny"));

    private static final List<PluginSpec> DISPLAY = List.of(
            new PluginSpec("holograms", "DecentHolograms", "DecentHolograms"),
            new PluginSpec("holograms", "FancyHolograms", "FancyHolograms"),
            new PluginSpec("npcs", "Citizens", "Citizens"),
            new PluginSpec("npcs", "FancyNpcs", "FancyNpcs"),
            new PluginSpec("maps", "BlueMap", "BlueMap"),
            new PluginSpec("maps", "dynmap", "dynmap"),
            new PluginSpec("maps", "squaremap", "squaremap"),
            new PluginSpec("placeholders", "PlaceholderAPI", "PlaceholderAPI"),
            new PluginSpec("packets", "packetevents", "packetevents"),
            new PluginSpec("proxy", "floodgate", "floodgate"));

    private ModuleContext ctx;
    private ConfigHandle<IntegrationsConfig> config;
    private CompositeProtectionService protection;
    /** Read from the LuckPerms event thread and from other modules, written on enable: must be volatile. */
    private volatile CachedMetaService meta;
    private final Map<String, String> inactiveProtection = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "integrations";
    }

    @Override
    public String description() {
        return "Optional protection, LuckPerms/Vault metadata and plugin status (/integrations)";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.inactiveProtection.clear();
        this.config = ctx.config(IntegrationsConfig.class, IntegrationsConfig::new);

        this.protection = new CompositeProtectionService(
                player -> player.hasPermission(PERM_BYPASS), config::get, ctx.logger());
        registerProtectionHooks();

        this.meta = createMetaService();
        // Subscribe only after the field above is assigned: the LuckPerms callback reads it.
        subscribeMeta();
        ctx.service(ProtectionService.class, protection);
        ctx.service(MetaService.class, meta);

        ctx.listen(this);
        ctx.permission(PERM_STATUS, PermissionDefault.OP, "Show detected protection/meta/display integrations");
        ctx.permission(PERM_BYPASS, PermissionDefault.OP, "Skip Atrium's protection checks");
        ctx.command(CommandSpec.of(Cmds.literal("integrations", PERM_STATUS)
                .executes(this::status)
                .then(Cmds.literal("status").executes(this::status))
                .build(), "Show detected integrations and whether they are active", "integration"));

        if (config.get().logHooks) {
            for (ProtectionHook hook : protection.hooks()) {
                ctx.logger().info("Protection hook {} {} active", hook.displayName(), hook.version());
            }
            ctx.logger().info("Metadata provider {} ({}) active", meta.provider().displayName(),
                    meta.provider().id());
        }

        // A provider that needs the player's own thread (Vault) must fill the cache on enable, otherwise the
        // first async chat read would find nothing and - before this fix - cache an empty value for good.
        for (Player online : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(online, () -> {
                CachedMetaService current = meta;
                if (current != null) {
                    current.refresh(online.getUniqueId());
                }
            }, null);
        }
    }

    @Override
    public void disable() {
        if (meta != null) {
            try {
                meta.provider().close();
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot close metadata provider {}", meta.provider().id(), e);
            }
            meta.invalidateAll();
        }
        protection = null;
        meta = null;
    }

    // ---- setup ---------------------------------------------------------------------------------

    private void registerProtectionHooks() {
        for (ProtectionSpec spec : PROTECTION) {
            if (!config.get().protectionHook(spec.id())) {
                inactiveProtection.put(spec.id(), "integrations.reason-disabled-config");
                continue;
            }
            if (!Bukkit.getPluginManager().isPluginEnabled(spec.plugin())) {
                inactiveProtection.put(spec.id(), pluginReason(spec.plugin()));
                continue;
            }
            try {
                protection.addHook(createProtectionHook(spec.id()));
            } catch (RuntimeException | LinkageError e) {
                inactiveProtection.put(spec.id(), "integrations.reason-api");
                ctx.logger().warn("{} is enabled but its protection API cannot be used; hook disabled",
                        spec.plugin(), e);
            }
        }
    }

    private ProtectionHook createProtectionHook(String id) {
        return switch (id) {
            case "worldguard" -> new WorldGuardHook();
            case "griefprevention" -> new GriefPreventionHook();
            case "lands" -> new LandsHook(ctx.plugin());
            case "towny" -> new TownyHook();
            default -> throw new IllegalArgumentException("Unknown protection hook " + id);
        };
    }

    private CachedMetaService createMetaService() {
        IntegrationsConfig cfg = config.get();
        IntegrationsConfig.MetaBackend wanted = cfg.metaBackend();
        boolean luckPerms = MetaAvailability.luckPerms();
        boolean vault = MetaAvailability.vault();
        IntegrationsConfig.MetaBackend choice = MetaSelection.choose(wanted, luckPerms, vault);

        MetaProvider provider = switch (choice) {
            case LUCKPERMS -> luckPerms();
            case VAULT -> vault();
            case AUTO, NONE -> new EmptyMetaProvider();
        };
        if (provider == null && wanted == IntegrationsConfig.MetaBackend.AUTO) {
            provider = vault ? vault() : null;
        }
        if (provider == null) {
            provider = new EmptyMetaProvider();
        }
        return new CachedMetaService(provider, ctx.logger());
    }

    private MetaProvider luckPerms() {
        try {
            return new LuckPermsMetaProvider();
        } catch (RuntimeException | LinkageError e) {
            ctx.logger().warn("LuckPerms is enabled but its metadata API cannot be used", e);
            return null;
        }
    }

    private void subscribeMeta() {
        CachedMetaService current = meta;
        if (current != null && current.provider() instanceof LuckPermsMetaProvider provider) {
            provider.subscribe(ctx.plugin(), this::invalidateMeta);
        }
    }

    private MetaProvider vault() {
        try {
            if (!VaultMetaProvider.available()) {
                return null;
            }
            return VaultMetaProvider.create();
        } catch (RuntimeException | LinkageError e) {
            ctx.logger().warn("Vault is enabled but its Chat API cannot be used", e);
            return null;
        }
    }

    private void invalidateMeta(java.util.UUID player) {
        CachedMetaService current = meta;
        if (current != null) {
            current.invalidate(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        String pluginName = event.getPlugin().getName();
        ProtectionSpec spec = PROTECTION.stream().filter(s -> s.plugin().equals(pluginName)).findFirst().orElse(null);
        if (spec == null || protection == null || !config.get().protectionHook(spec.id())
                || protection.hook(spec.id()) != null) {
            return;
        }
        // Some plugins finish their own enable after firing the event; give them one tick.
        ctx.track(ctx.scheduler().globalLater(() -> addLateProtection(spec), 1));
    }

    private void addLateProtection(ProtectionSpec spec) {
        if (protection == null || protection.hook(spec.id()) != null
                || !Bukkit.getPluginManager().isPluginEnabled(spec.plugin())) {
            return;
        }
        try {
            protection.addHook(createProtectionHook(spec.id()));
            inactiveProtection.remove(spec.id());
            if (config.get().logHooks) {
                ctx.logger().info("Protection hook {} became available after startup", spec.plugin());
            }
        } catch (RuntimeException | LinkageError e) {
            inactiveProtection.put(spec.id(), "integrations.reason-api");
            ctx.logger().warn("{} was enabled later but its protection API cannot be used", spec.plugin(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        CachedMetaService current = meta;
        if (current != null) {
            Player player = event.getPlayer();
            ctx.scheduler().entity(player, () -> current.refresh(player.getUniqueId()), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        CachedMetaService current = meta;
        if (current != null) {
            current.invalidate(event.getPlayer().getUniqueId());
        }
    }

    // ---- /integrations -------------------------------------------------------------------------

    private int status(CommandContext<CommandSourceStack> command) {
        CommandSender sender = command.getSource().getSender();
        IntegrationsConfig cfg = config.get();
        List<StatusEntry> entries = statusEntries();
        ctx.messages().send(sender, "integrations.status-header");
        String section = null;
        int shown = 0;
        for (StatusEntry entry : entries) {
            if (!cfg.showMissing && !entry.active()) {
                continue;
            }
            if (!entry.section().equals(section)) {
                section = entry.section();
                ctx.messages().send(sender, "integrations.status-section",
                        Msg.component("section", ctx.messages().render(sender, "integrations.section." + section)));
            }
            shown++;
            if (entry.active()) {
                if (entry.detailKey() == null) {
                    ctx.messages().send(sender, "integrations.status-active",
                            Msg.unparsed("name", entry.name()), Msg.unparsed("version", entry.version()));
                } else {
                    ctx.messages().send(sender, "integrations.status-active-detail",
                            Msg.unparsed("name", entry.name()), Msg.unparsed("version", entry.version()),
                            Msg.component("detail", ctx.messages().render(sender, entry.detailKey())));
                }
            } else {
                ctx.messages().send(sender, "integrations.status-inactive",
                        Msg.unparsed("name", entry.name()), Msg.unparsed("version", entry.version()),
                        Msg.component("detail", ctx.messages().render(sender, entry.detailKey())));
            }
        }
        ctx.messages().send(sender, "integrations.status-footer", Msg.number("count", shown));
        return Cmds.OK;
    }

    private List<StatusEntry> statusEntries() {
        List<StatusEntry> entries = new ArrayList<>();
        CompositeProtectionService checks = protection;
        if (checks != null) {
            for (ProtectionSpec spec : PROTECTION) {
                ProtectionHook hook = checks.hook(spec.id());
                if (hook != null) {
                    entries.add(new StatusEntry("protection", hook.displayName(), hook.version(), true, null));
                } else {
                    entries.add(new StatusEntry("protection", spec.plugin(), pluginVersion(spec.plugin()), false,
                            inactiveProtection.getOrDefault(spec.id(), "integrations.reason-not-enabled")));
                }
            }
        }

        CachedMetaService cached = meta;
        if (cached != null) {
            MetaProvider provider = cached.provider();
            boolean active = !(provider instanceof EmptyMetaProvider);
            entries.add(new StatusEntry("meta", provider.displayName(), provider.version(), active,
                    active ? null : "integrations.reason-empty"));
        }

        ctx.services().get(HologramService.class).ifPresent(service ->
                entries.add(new StatusEntry("holograms", "Atrium holograms",
                        ctx.plugin().getPluginMeta().getVersion(), true, "integrations.reason-service")));

        for (PluginSpec spec : DISPLAY) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(spec.plugin());
            boolean active = plugin != null && plugin.isEnabled();
            entries.add(new StatusEntry(spec.section(), spec.display(), pluginVersion(spec.plugin()), active,
                    active ? null : pluginReason(spec.plugin())));
        }

        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        boolean vaultActive = vault != null && vault.isEnabled();
        entries.add(new StatusEntry("vault", "Vault", pluginVersion("Vault"), vaultActive,
                vaultActive ? null : pluginReason("Vault")));
        if (vaultActive) {
            try {
                String chat = VaultStatus.chatProvider();
                entries.add(new StatusEntry("vault", chat == null ? "Vault Chat" : "Vault Chat (" + chat + ")",
                        pluginVersion("Vault"), chat != null, chat == null ? "integrations.reason-no-provider" : null));
            } catch (RuntimeException | LinkageError e) {
                entries.add(new StatusEntry("vault", "Vault Chat", pluginVersion("Vault"), false,
                        "integrations.reason-api"));
            }
        }
        return entries;
    }

    private String pluginReason(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin == null ? "integrations.reason-not-installed" : "integrations.reason-plugin-disabled";
    }

    private static String pluginVersion(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin == null ? "-" : plugin.getPluginMeta().getVersion();
    }

    private record ProtectionSpec(String id, String plugin) {}

    private record PluginSpec(String section, String display, String plugin) {}

    private record StatusEntry(String section, String name, String version, boolean active, String detailKey) {}
}
