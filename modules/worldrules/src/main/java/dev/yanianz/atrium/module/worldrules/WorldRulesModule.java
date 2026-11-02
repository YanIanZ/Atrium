package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.api.worldrules.ArmorEffectService;
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
    private PlayerTimeManager times;
    private PlayerRulesListener playerRules;
    private SleepListener sleep;
    private ResurrectionManager resurrection;
    private LeatherAnimationManager animation;
    private WorldFlagsListener flags;
    private TimeRateManager timeRate;

    @Override
    public String id() {
        return "worldrules";
    }

    @Override
    public String description() {
        return "Per-world rules, game rules, compass targets, player time/weather and block cycling";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(WorldRulesConfig.class, WorldRulesConfig::new);
        this.config.get().validate(message -> ctx.logger().warn("worldrules: {}", message));
        ctx.migrate(WorldRulesStore.MIGRATIONS);
        WorldRulesStore store = new WorldRulesStore(ctx.database());
        ctx.component(store.component);

        this.armor = new ArmorEffectManager(ctx, config);
        this.armor.recompile();
        this.compass = new CompassManager(ctx, config);
        this.animation = new LeatherAnimationManager(ctx, config);
        this.listener = new WorldRulesListener(ctx, config, armor, compass, animation);
        this.times = new PlayerTimeManager(ctx, store);
        this.playerRules = new PlayerRulesListener(ctx, config, times);
        this.sleep = new SleepListener(ctx, config);
        this.resurrection = new ResurrectionManager(ctx, config);
        this.flags = new WorldFlagsListener(ctx, config, animation);
        this.timeRate = new TimeRateManager(ctx, config);
        ctx.listen(listener);
        ctx.listen(playerRules);
        ctx.listen(sleep);
        ctx.listen(resurrection);
        ctx.listen(flags);
        ctx.service(ArmorEffectService.class, armor::refresh);
        this.timeRate.recompile();

        ctx.permission("atrium.gamerule", PermissionDefault.OP, "Read and change world game rules");
        ctx.permission("atrium.compass", PermissionDefault.TRUE, "Point your own compass");
        ctx.permission("atrium.compass.others", PermissionDefault.OP, "Point another player's compass");
        ctx.permission("atrium.blockcycle", PermissionDefault.OP, "Cycle the block state you are looking at");
        ctx.permission("atrium.worldrules.gamemode.bypass", PermissionDefault.OP,
                "Ignore a world's forced game mode");
        ctx.permission(PTimeCommand.PERMISSION, PermissionDefault.TRUE, "Set your own in-game time");
        ctx.permission(PTimeCommand.OTHERS, PermissionDefault.OP, "Set the in-game time of another player");
        ctx.permission(PWeatherCommand.PERMISSION, PermissionDefault.TRUE, "Set your own client weather");
        ctx.permission(PWeatherCommand.OTHERS, PermissionDefault.OP, "Set the client weather of another player");
        ctx.permission(WeatherCommand.PERMISSION, PermissionDefault.OP, "Change the weather of a world");
        ctx.permission(PlayerRulesListener.PERM_KEEP_INVENTORY, PermissionDefault.FALSE,
                "Keep your inventory on death (when the module option is enabled)");
        ctx.permission(PlayerRulesListener.PERM_KEEP_XP, PermissionDefault.FALSE,
                "Keep your experience level on death (when the module option is enabled)");
        ctx.permission(PlayerRulesListener.PERM_VOID_BYPASS, PermissionDefault.OP,
                "Never be rescued from the void");
        ctx.permission(SleepListener.BYPASS_PERMISSION, PermissionDefault.OP,
                "Use commands while lying in a bed");
        ctx.permission(ResurrectionManager.PERM_USE, PermissionDefault.TRUE,
                "Use the custom totem resurrection");
        ctx.permission(WorldFlagsListener.FLY_BYPASS, PermissionDefault.OP,
                "Ignore a world's flight limit");
        ctx.permission(LeatherAnimationManager.PERMISSION, PermissionDefault.TRUE,
                "Opt in to the animated leather armour colours");
        ctx.permission(LeatherAnimationManager.OTHERS, PermissionDefault.OP,
                "Change the leather armour animation of another player");

        new GameruleCommand(ctx).register();
        new CompassCommand(ctx, compass).register();
        new BlockCycleCommand(ctx, config).register();
        new PTimeCommand(ctx, times).register();
        new PWeatherCommand(ctx).register();
        new WeatherCommand(ctx).register();
        new ArmorColorCommand(ctx, config, animation).register();
    }

    @Override
    public void reload() {
        config.get().validate(message -> ctx.logger().warn("worldrules: {}", message));
        armor.recompile();
        listener.reload();
        sleep.reload();
        resurrection.reload();
        playerRules.reload();
        animation.reload();
        flags.reload();
        timeRate.recompile();
    }

    @Override
    public void disable() {
        if (compass != null) {
            compass.stopAll();
        }
        if (armor != null) {
            armor.removeAll();
        }
        if (sleep != null) {
            sleep.shutdown();
        }
        if (resurrection != null) {
            resurrection.shutdown();
        }
        if (animation != null) {
            animation.stopAll();
        }
        if (timeRate != null) {
            timeRate.stop();
        }
        if (flags != null) {
            flags.shutdown();
        }
    }
}
