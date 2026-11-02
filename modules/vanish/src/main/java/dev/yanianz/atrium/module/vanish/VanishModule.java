package dev.yanianz.atrium.module.vanish;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/**
 * {@code /vanish [player]}, {@code /vanish pickup}. Publishes {@link VanishService}.
 *
 * <p>Guarantees: the change event carries the new state and fires before anything changes;
 * hiding is symmetric and re-checked periodically, so permission changes apply live; every vibration
 * block is covered; staff cannot toggle the vanish of someone with a higher level than they can see.
 */
public final class VanishModule implements AtriumModule {
    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<VanishConfig> config;
    private VanishManager manager;
    private volatile TaskHandle reminder = TaskHandle.NOOP;
    private volatile TaskHandle resync = TaskHandle.NOOP;

    @Override
    public String id() {
        return "vanish";
    }

    @Override
    public String description() {
        return "Vanish with levels, silent containers and no interaction side effects";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(VanishConfig.class, VanishConfig::new);
        ctx.migrate(VanishStore.MIGRATIONS);
        VanishStore store = new VanishStore(ctx.database());
        ctx.component(store.component);
        this.manager = new VanishManager(ctx, store, config);

        ctx.permission(VanishManager.PERM, PermissionDefault.OP, "Toggle own vanish");
        ctx.permission(VanishManager.PERM_OTHERS, PermissionDefault.OP, "Toggle vanish of other players");
        ctx.permission(VanishManager.PERM_SEE, PermissionDefault.OP,
                "See vanished players up to the atrium.vanish.see.<level> level (default 1)");
        ctx.permission(VanishManager.PERM_SILENT_MODIFY, PermissionDefault.OP,
                "Edit containers opened silently while vanished");

        ctx.listen(new VanishListener(ctx, manager, config));
        SilentContainers silent = new SilentContainers(ctx, manager, config);
        ctx.listen(silent);
        ctx.track(silent);
        ctx.service(VanishService.class, manager);
        startTimers();
        restoreOnline(store);

        ctx.command(CommandSpec.of(Cmds.literal("vanish", VanishManager.PERM)
                .executes(this::toggle)
                .then(Cmds.literal("pickup").executes(this::pickup))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::toggle))
                .build(), "Hide yourself or another player from other players; /vanish pickup toggles item pickup", "v"));
    }

    /** After a module reload, players already online get their stored state back. */
    private void restoreOnline(VanishStore store) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            store.load(uuid).whenComplete((state, error) -> {
                if (error != null) {
                    ctx.logger().error("Could not load the vanish state of {}", player.getName(), error);
                    return;
                }
                ctx.scheduler().entity(player, () -> manager.restore(player, state, null), null);
            });
        }
    }

    private void startTimers() {
        VanishConfig cfg = config.get();
        reminder.cancel();
        resync.cancel();
        if (cfg.actionBarIntervalSeconds > 0) {
            long period = 20L * Math.min(cfg.actionBarIntervalSeconds, 300);
            reminder = ctx.track(ctx.scheduler().globalTimer(manager::remindAll, period, period));
        }
        long resyncPeriod = 20L * Math.clamp(cfg.resyncIntervalSeconds, 1, 300);
        resync = ctx.track(ctx.scheduler().globalTimer(manager::resyncAll, resyncPeriod, resyncPeriod));
    }

    @Override
    public void reload() {
        startTimers();
    }

    @Override
    public void disable() {
        reminder.cancel();
        resync.cancel();
        if (manager != null) {
            manager.shutdown();
        }
    }

    // ---- commands ----

    private int toggle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", VanishManager.PERM_OTHERS);
        CommandSender sender = c.getSource().getSender();
        boolean self = target.equals(sender);
        // Staff may only change players whose vanish level they can see.
        if (!self && !manager.seesWhenVanished(sender, target)) {
            throw cmds.fail(c.getSource(), "vanish.level-too-high", Msg.unparsed("player", target.getName()));
        }
        String name = target.getName();
        UUID uuid = target.getUniqueId();
        manager.toggle(target, sender).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Vanish toggle of {} failed", name, error);
                reply(sender, "vanish.failed");
                return;
            }
            switch (result) {
                case CANCELLED -> reply(sender, "vanish.cancelled");
                case UNCHANGED -> reply(sender, "vanish.target-left", Msg.unparsed("player", name));
                case CHANGED -> {
                    boolean now = manager.isOnlineVanished(uuid);
                    String key = now ? "vanish.enabled" : "vanish.disabled";
                    // The change is in effect; confirm it only once it is stored.
                    ctx.users().saveNow(uuid).whenComplete((v, saveError) -> {
                        if (saveError != null) {
                            ctx.logger().warn("Saving the vanish state of {} failed; it will be retried", name,
                                    saveError);
                            reply(sender, "vanish.save-failed", Msg.unparsed("player", name));
                            return;
                        }
                        ctx.scheduler().entity(target, () -> ctx.messages().send(target, key), null);
                        if (!self) {
                            reply(sender, key + "-other", Msg.unparsed("player", name));
                        }
                    });
                }
            }
        });
        return Cmds.OK;
    }

    private int pickup(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        manager.togglePickup(player)
                .thenCompose(enabled -> ctx.users().saveNow(player.getUniqueId()).thenApply(v -> enabled))
                .whenComplete((enabled, error) -> {
                    if (error != null) {
                        ctx.logger().error("Saving the vanish pickup toggle of {} failed", player.getName(), error);
                        reply(player, "vanish.failed");
                        return;
                    }
                    reply(player, enabled ? "vanish.pickup-enabled" : "vanish.pickup-disabled");
                });
        return Cmds.OK;
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }
}
