package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.permissions.PermissionDefault;

/** Per-world gameplay rules, game rule command, compass and block cycling. */
public final class WorldRulesModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<WorldRulesConfig> config;
    private ArmorEffectManager armor;
    private CompassManager compass;
    private WorldRulesListener listener;

    @Override
    public String id() {
        return "worldrules";
    }

    @Override
    public String description() {
        return "Per-world rules, game rules, compass targets and block cycling";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(WorldRulesConfig.class, WorldRulesConfig::new);
        this.config.get().validate(message -> ctx.logger().warn("worldrules: {}", message));
        this.armor = new ArmorEffectManager(ctx, config);
        this.armor.recompile();
        this.compass = new CompassManager(ctx, config);
        this.listener = new WorldRulesListener(ctx, config, armor, compass);
        ctx.listen(listener);

        ctx.permission("atrium.gamerule", PermissionDefault.OP, "Read and change world game rules");
        ctx.permission("atrium.compass", PermissionDefault.TRUE, "Point your own compass");
        ctx.permission("atrium.compass.others", PermissionDefault.OP, "Point another player's compass");
        ctx.permission("atrium.blockcycle", PermissionDefault.OP, "Cycle the block state you are looking at");
        ctx.permission("atrium.worldrules.gamemode.bypass", PermissionDefault.OP,
                "Ignore a world's forced game mode");

        new GameruleCommand(ctx).register();
        new CompassCommand(ctx, compass).register();
        new BlockCycleCommand(ctx, config).register();
    }

    @Override
    public void reload() {
        config.get().validate(message -> ctx.logger().warn("worldrules: {}", message));
        armor.recompile();
        listener.reload();
    }

    @Override
    public void disable() {
        if (compass != null) {
            compass.stopAll();
        }
        if (armor != null) {
            armor.removeAll();
        }
    }
}
