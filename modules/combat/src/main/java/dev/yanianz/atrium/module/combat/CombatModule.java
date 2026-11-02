package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.api.combat.CombatService;
import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/** Combat tag, damage control and mob no-target. */
public final class CombatModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<CombatConfig> config;
    private CombatTracker tracker;
    private NotargetManager notarget;

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public String description() {
        return "Combat tag, combat-log punishment, damage multipliers and mob no-target";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("player", "vanish", "integrations");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(CombatConfig.class, CombatConfig::new);
        this.config.get().validate(message -> ctx.logger().warn("combat: {}", message));

        @Nullable FlightService flight = ctx.services().get(FlightService.class).orElse(null);
        @Nullable VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        @Nullable ProtectionService protection = ctx.services().get(ProtectionService.class).orElse(null);

        this.tracker = new CombatTracker(ctx, config, flight, vanish);
        ctx.service(CombatService.class, tracker);

        ctx.migrate(NotargetStore.MIGRATIONS);
        NotargetStore store = new NotargetStore(ctx.database());
        ctx.component(store.component);
        this.notarget = new NotargetManager(ctx, config, store);

        ctx.listen(new CombatListener(ctx, config, tracker, protection, vanish));
        ctx.listen(notarget);
        ctx.listen(new DamageControlListener(config));

        ctx.permission(CombatCommands.STATUS, PermissionDefault.TRUE, "Show your combat tag status");
        ctx.permission(CombatCommands.STATUS_OTHERS, PermissionDefault.OP,
                "Show the combat tag status of another player");
        ctx.permission(CombatCommands.ADMIN, PermissionDefault.OP, "Tag or untag players for combat");
        ctx.permission(CombatTracker.BYPASS_PERMISSION, PermissionDefault.OP,
                "Never be combat tagged, and use blocked commands while tagged");
        ctx.permission(DamageControlListener.BYPASS_PERMISSION, PermissionDefault.OP,
                "Ignore every damage-control rule");
        ctx.permission(CombatCommands.NOTARGET, PermissionDefault.TRUE, "Make mobs ignore you");
        ctx.permission(CombatCommands.NOTARGET_OTHERS, PermissionDefault.OP,
                "Toggle mob no-target for another player");
        ctx.permission(DamageControlCommand.PERMISSION, PermissionDefault.OP,
                "List the configured damage-control rules");

        new CombatCommands(ctx, config, tracker, notarget).register();
        new DamageControlCommand(ctx, config).register();
    }

    @Override
    public void reload() {
        config.get().validate(message -> ctx.logger().warn("combat: {}", message));
        tracker.refreshAll();
    }

    @Override
    public void disable() {
        if (tracker != null) {
            tracker.shutdown();
        }
        if (notarget != null) {
            notarget.shutdown();
        }
    }
}
