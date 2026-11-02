package dev.yanianz.atrium.module.effects;

import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Event effects (particles, sounds, titles, fireworks) and persistent fixed effects.
 *
 * <p>All spawns are scheduled on the region or entity thread that owns the location. Fixed effects only
 * run while a player is near; see {@link FixedEffects}.
 */
public final class EffectsModule implements AtriumModule, Listener {
    static final String PERM_USE = "atrium.effects";
    static final String PERM_TEST = "atrium.effects.test";
    static final String PERM_TEST_OTHERS = "atrium.effects.test.others";
    static final String PERM_RELOAD = "atrium.effects.reload";
    static final String PERM_FIXED = "atrium.effects.fixed";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<EffectsConfig> config;
    private volatile Map<String, EffectStyle> styles = Map.of();
    private EffectRuntime runtime;
    private FixedEffects fixed;
    private FireworkGuard guard;
    private volatile int warnings;

    @Override
    public String id() {
        return "effects";
    }

    @Override
    public String description() {
        return "Event effects (particles, sounds, titles, fireworks) and persistent fixed effects";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("vanish");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(EffectsConfig.class, EffectsConfig::new);
        EffectsConfig loaded = config.get();
        loaded.fillMissingEvents();
        applyStyles(EffectParser.parse(loaded.events));

        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        NamespacedKey fireworkKey = new NamespacedKey(ctx.plugin(), "effect_firework");
        this.guard = new FireworkGuard(fireworkKey);
        ctx.listen(guard);
        this.runtime = new EffectRuntime(ctx, this::styles, vanish, fireworkKey);

        ctx.migrate(FixedEffectStore.MIGRATIONS);
        this.fixed = new FixedEffects(ctx, new FixedEffectStore(ctx.database(), ctx.logger()));
        fixed.load();
        fixed.configure(loaded.fixedCheckIntervalTicks, loaded.fixedViewRadius);
        fixed.start();

        ctx.listen(this);

        registerPermissions();
        new EffectsCommands(this).register();
    }

    private void registerPermissions() {
        ctx.permission(PERM_USE, PermissionDefault.TRUE, "List and preview event effects with /effects");
        ctx.permission(PERM_TEST, PermissionDefault.OP, "Preview an event effect on yourself");
        ctx.permission(PERM_TEST_OTHERS, PermissionDefault.OP, "Preview an event effect on another player");
        ctx.permission(PERM_RELOAD, PermissionDefault.OP, "Reload the effects configuration");
        ctx.permission(PERM_FIXED, PermissionDefault.OP,
                "Create, list, move, teleport to, edit and delete persistent fixed effects");
    }

    private void applyStyles(EffectParser.Result result) {
        this.styles = result.effects();
        this.warnings = result.warnings().size();
        for (String warning : result.warnings()) {
            ctx.logger().warn("effects.yml: {}", warning);
        }
    }

    /** Re-reads the config; returns the number of warnings. Throws when the file cannot be parsed. */
    int reloadEffects() throws Exception {
        EffectsConfig loaded = config.reload();
        loaded.fillMissingEvents();
        applyStyles(EffectParser.parse(loaded.events));
        fixed.configure(loaded.fixedCheckIntervalTicks, loaded.fixedViewRadius);
        return warnings;
    }

    @Override
    public void reload() throws Exception {
        reloadEffects();
    }

    @Override
    public void disable() {
        if (fixed != null) {
            fixed.shutdown();
        }
        styles = Map.of();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPlayedBefore()) {
            // Wait a moment so the client can render the title and the firework after the join screen.
            runtime.fire(EffectKeys.FIRST_JOIN, player, player.getLocation(), new EffectRuntime.Fire(true, true, true, 10));
        } else {
            runtime.fire(EffectKeys.JOIN, player, player.getLocation(), new EffectRuntime.Fire(true, true, true, 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Shown to others: the leaving player no longer needs particles or text.
        runtime.fire(EffectKeys.QUIT, player, player.getLocation(), EffectRuntime.Fire.OTHERS_ONLY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        runtime.fire(EffectKeys.DEATH, player, player.getLocation(), EffectRuntime.Fire.NORMAL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        EffectStyle generic = styles.get(EffectKeys.KILL);
        EffectStyle style = EffectKeys.forKill(styles, event.getEntityType(), generic);
        if (style == null) {
            return;
        }
        if (!style.entityTypes().isEmpty() && !style.entityTypes().contains(event.getEntityType())) {
            return;
        }
        runtime.fire(style, killer, event.getEntity().getLocation(), EffectRuntime.Fire.NORMAL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        if (event.getNewLevel() <= event.getOldLevel()) {
            return;
        }
        Player player = event.getPlayer();
        runtime.fire(EffectKeys.LEVEL_UP, player, player.getLocation(), EffectRuntime.Fire.NORMAL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var advancement = event.getAdvancement();
        if (advancement.getDisplay() == null) {
            return;
        }
        // Recipe unlocks are advancements too, but announcing them would spam every player.
        if (advancement.getKey().getKey().startsWith("recipes/")) {
            return;
        }
        Player player = event.getPlayer();
        runtime.fire(EffectKeys.ADVANCEMENT, player, player.getLocation(), EffectRuntime.Fire.NORMAL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() == null) {
            return;
        }
        // Tiny corrections (anti-cheat, seat offset) should not spam departure and arrival effects.
        if (event.getFrom().getWorld() == event.getTo().getWorld()
                && event.getFrom().distanceSquared(event.getTo()) < 4.0) {
            return;
        }
        Player player = event.getPlayer();
        runtime.fire(EffectKeys.TELEPORT_DEPART, player, event.getFrom(), EffectRuntime.Fire.NORMAL);
        // teleportAsync fires this event before the player moves; wait for the destination chunk.
        runtime.fire(EffectKeys.TELEPORT_ARRIVE, player, event.getTo(), new EffectRuntime.Fire(true, true, true, 2));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        runtime.fire(EffectKeys.RESPAWN, player, event.getRespawnLocation(),
                new EffectRuntime.Fire(true, true, true, 2));
    }

    // --- package access for the command classes ------------------------------------------------

    ModuleContext ctx() {
        return ctx;
    }

    Cmds cmds() {
        return cmds;
    }

    Messages messages() {
        return ctx.messages();
    }

    Map<String, EffectStyle> styles() {
        return styles;
    }

    EffectRuntime runtime() {
        return runtime;
    }

    FixedEffects fixed() {
        return fixed;
    }

    EffectsConfig config() {
        return config.get();
    }

    org.slf4j.Logger logger() {
        return ctx.logger();
    }
}
