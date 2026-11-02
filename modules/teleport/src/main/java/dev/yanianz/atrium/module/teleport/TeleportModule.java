package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bukkit.permissions.PermissionDefault;

/** Teleports with warmup, cooldown, safe landing and /back; publishes {@link TeleportService}. */
public final class TeleportModule implements AtriumModule {
    private TeleportServiceImpl service;

    @Override
    public String id() {
        return "teleport";
    }

    @Override
    public String description() {
        return "Teleports: /tp /tphere /tpall /tpa /tpahere /tpaccept /tpdeny /tpacancel /tptoggle /back /top /spawn /setspawn";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        ConfigHandle<TeleportConfig> config = ctx.config(TeleportConfig.class, TeleportConfig::new);
        ctx.migrate(TeleportStore.MIGRATIONS);
        TeleportStore store = new TeleportStore(ctx.database());
        ctx.component(store.component);
        // SourbyCraft MultiProfile: the /back history may follow the active profile.
        ctx.profileScoped(new BackProfileData(ctx, store));

        service = new TeleportServiceImpl(ctx, config, store);
        service.loadSpawns(store.loadSpawns().get(30, TimeUnit.SECONDS));
        service.loadCooldowns(store.loadCooldowns(System.currentTimeMillis()).get(30, TimeUnit.SECONDS));
        ctx.listen(service);

        registerPermissions(ctx, config.get());

        new TeleportCommands(ctx, service, config).register();
        RequestCommands requests = new RequestCommands(ctx, service, config);
        requests.register();
        ctx.listen(requests);
        SpawnCommands spawns = new SpawnCommands(ctx, service, config);
        spawns.register();
        ctx.listen(spawns);

        ctx.track(ctx.scheduler().asyncTimer(() -> {
            try {
                requests.expireTick();
                service.purge();
            } catch (RuntimeException e) {
                ctx.logger().error("Teleport housekeeping failed", e);
            }
        }, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        ctx.track(ctx.scheduler().asyncTimer(() -> store.purgeCooldowns(System.currentTimeMillis()).exceptionally(error -> {
            ctx.logger().warn("Purging expired teleport cooldowns failed", error);
            return 0;
        }), Duration.ofMinutes(1), Duration.ofMinutes(10)));

        ctx.service(TeleportService.class, service);
        ctx.service(ExtendedTeleportService.class, service);
        // Stored destinations in worlds that are not loaded (AWF/profile worlds) go through WorldResolver.
        ctx.service(dev.yanianz.atrium.api.teleport.StoredTeleportService.class, new StoredTeleports(ctx, service));
        // Import tools: spawn points and /back entries (the importer module).
        TeleportAdmin admin = new TeleportAdmin(ctx, service, store, config);
        ctx.service(dev.yanianz.atrium.api.teleport.SpawnAdminService.class, admin);
        ctx.service(dev.yanianz.atrium.api.teleport.BackHistoryAdminService.class, admin);
    }

    private static void registerPermissions(ModuleContext ctx, TeleportConfig config) {
        ctx.permission(TeleportCommands.PERM_TP, PermissionDefault.OP, "Teleport to players and coordinates");
        ctx.permission(TeleportCommands.PERM_TP_OTHERS, PermissionDefault.OP, "Teleport other players");
        ctx.permission(TeleportCommands.PERM_TPHERE, PermissionDefault.OP, "Teleport a player to you");
        ctx.permission(TeleportCommands.PERM_TPALL, PermissionDefault.OP, "Teleport every player to you");
        ctx.permission(TeleportCommands.PERM_TOP, PermissionDefault.OP, "Teleport to the highest block above you");
        ctx.permission(TeleportCommands.PERM_BACK, PermissionDefault.TRUE, "Return to the previous location");
        ctx.permission(TeleportCommands.PERM_BACK_OTHERS, PermissionDefault.OP, "Send other players back with /back <player>");
        ctx.permission(TeleportServiceImpl.PERM_BACK_ON_DEATH,
                config.back.onDeathForEveryone ? PermissionDefault.TRUE : PermissionDefault.OP,
                "Return to the death location with /back");
        ctx.permission(TeleportCommands.PERM_TOGGLE_BYPASS, PermissionDefault.OP, "Teleport to players who disabled teleports");
        ctx.permission(RequestCommands.PERM_TPA, PermissionDefault.TRUE, "Request to teleport to a player");
        ctx.permission(RequestCommands.PERM_TPAHERE, PermissionDefault.TRUE, "Request a player to teleport to you");
        ctx.permission(RequestCommands.PERM_TPACCEPT, PermissionDefault.TRUE, "Accept teleport requests");
        ctx.permission(RequestCommands.PERM_TPDENY, PermissionDefault.TRUE, "Deny teleport requests");
        ctx.permission(RequestCommands.PERM_TPACANCEL, PermissionDefault.TRUE, "Cancel own teleport requests");
        ctx.permission(RequestCommands.PERM_TPTOGGLE, PermissionDefault.TRUE, "Refuse or allow teleport requests");
        ctx.permission(SpawnCommands.PERM_SPAWN, PermissionDefault.TRUE, "Teleport to the spawn");
        ctx.permission(SpawnCommands.PERM_SPAWN_OTHERS, PermissionDefault.OP, "Send other players to the spawn");
        ctx.permission(SpawnCommands.PERM_SETSPAWN, PermissionDefault.OP, "Set spawn points");
        ctx.permission(TeleportServiceImpl.PERM_WARMUP_BYPASS, PermissionDefault.OP, "Skip the teleport warmup");
        ctx.permission(TeleportServiceImpl.PERM_COOLDOWN_BYPASS, PermissionDefault.OP, "Ignore teleport cooldowns");
    }

    @Override
    public void disable() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }
}
