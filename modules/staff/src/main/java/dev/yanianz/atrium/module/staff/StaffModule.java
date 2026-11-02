package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;

/**
 * Administrative tools that are safe on Folia: staff mode with a crash-safe saved inventory, sudo,
 * command spy and spectator sessions with a persisted return point.
 */
public final class StaffModule implements AtriumModule {
    private ModuleContext ctx;
    private StaffManager manager;
    private SpectateManager spectate;

    @Override
    public String id() {
        return "staff";
    }

    @Override
    public String description() {
        return "Staff mode, sudo, command spy and spectate";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("player", "vanish", "moderation");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        ConfigHandle<StaffConfig> config = ctx.config(StaffConfig.class, StaffConfig::new);
        ctx.migrate(StaffStore.MIGRATIONS);
        StaffStore store = new StaffStore(ctx.database(), ctx.logger());
        ctx.component(store.preferences);
        ctx.component(store.spectate);

        StaffTools tools = new StaffTools(ctx.plugin(), ctx.messages());
        this.manager = new StaffManager(ctx, config, store, tools);
        this.spectate = new SpectateManager(ctx, store);
        ctx.listen(new StaffListener(ctx, config, manager, tools, store, spectate));
        new StaffCommands(ctx, manager, spectate, store).register();
        StaffCommands.registerPermissions(ctx);

        // Module (re)load while players are online: sessions left by the previous instance (disable only
        // persists) are restored now instead of on the next join. Delayed so user components are loaded.
        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            ctx.track(ctx.scheduler().entityLater(online, () -> {
                manager.restoreOnJoin(online);
                spectate.restoreOnJoin(online);
            }, null, 40));
        }
    }

    @Override
    public void disable() {
        if (spectate != null) {
            spectate.disable();
        }
        if (manager != null) {
            manager.disable();
        }
    }
}
