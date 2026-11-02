package dev.yanianz.atrium.module.player;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.potion.PotionEffect;

/**
 * Reference module: shows config, messages, permissions, a user component, a listener and
 * "self or other" commands that run their world changes on the target's own thread (Folia).
 */
public final class PlayerModule implements AtriumModule, Listener {
    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<PlayerConfig> config;
    private PlayerStore store;
    private FlightManager flight;
    private NameChangeWatcher nameChanges;

    @Override
    public String id() {
        return "player";
    }

    @Override
    public String description() {
        return "Player utilities: flight, health, gamemode, inventory and experience";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(PlayerConfig.class, PlayerConfig::new);
        ctx.migrate(PlayerStore.MIGRATIONS);
        this.store = new PlayerStore(ctx.database());
        ctx.component(store.component);
        ctx.component(store.identity);
        this.flight = new FlightManager(ctx);
        ctx.listen(flight);
        ctx.service(FlightService.class, flight);
        ctx.listen(this);
        new PlayerCommands(ctx, config, store).register();
        new InventoryCommands(ctx, config).register();
        new UtilityCommands(ctx, config, store).register();
        this.nameChanges = new NameChangeWatcher(ctx, config, store);
        nameChanges.register();

        ctx.permission("atrium.fly", PermissionDefault.OP, "Toggle own flight");
        ctx.permission("atrium.fly.others", PermissionDefault.OP, "Toggle flight of others");
        ctx.permission("atrium.heal", PermissionDefault.OP, "Heal yourself");
        ctx.permission("atrium.heal.others", PermissionDefault.OP, "Heal others");

        ctx.command(CommandSpec.of(Cmds.literal("fly", "atrium.fly")
                .executes(this::fly)
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::fly))
                .build(), "Toggle flight"));
        ctx.command(CommandSpec.of(Cmds.literal("heal", "atrium.heal")
                .executes(this::heal)
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::heal))
                .build(), "Restore health and hunger"));
    }

    private int fly(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", "atrium.fly.others");
        var sender = c.getSource().getSender();
        // Commands run on the sender's thread; the target may live in another region on Folia.
        ctx.scheduler().entity(target, () -> {
            // Toggle our own claim, not allowFlight: another feature (for example an elytra launch)
            // may legitimately hold flight at the same time.
            boolean enable = !flight.hasClaim(target, FlightManager.OWNER_FLY);
            if (enable) {
                flight.claim(target, FlightManager.OWNER_FLY);
            } else {
                flight.release(target, FlightManager.OWNER_FLY);
            }
            ctx.users().user(target).update(store.component, s -> s.withFlying(enable));
            String key = enable ? "player.fly-enabled" : "player.fly-disabled";
            ctx.messages().send(target, key);
            if (!target.equals(sender)) {
                reply(sender, key + "-other", Msg.unparsed("player", target.getName()));
            }
        }, null);
        return Cmds.OK;
    }

    private int heal(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", "atrium.heal.others");
        var sender = c.getSource().getSender();
        boolean clearEffects = config.get().healClearsEffects;
        ctx.scheduler().entity(target, () -> {
            var maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
            target.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
            target.setFoodLevel(20);
            target.setSaturation(20f);
            if (clearEffects) {
                target.setFireTicks(0);
                for (PotionEffect effect : target.getActivePotionEffects()) {
                    if (effect.getType().getEffectCategory() == org.bukkit.potion.PotionEffectType.Category.HARMFUL) {
                        target.removePotionEffect(effect.getType());
                    }
                }
            }
            ctx.messages().send(target, "player.healed");
            if (!target.equals(sender)) {
                reply(sender, "player.healed-other", Msg.unparsed("player", target.getName()));
            }
        }, null);
        return Cmds.OK;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> {
            if (config.get().restoreFlightOnJoin && player.hasPermission("atrium.fly")
                    && ctx.users().user(player).get(store.component).flying()) {
                flight.claim(player, FlightManager.OWNER_FLY);
            }
        }, null);
    }

    @Override
    public void disable() {
        if (nameChanges != null) {
            nameChanges.stop();
        }
        if (flight != null) {
            flight.clear();
        }
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }
}
