package dev.yanianz.atrium.module.player;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.permissions.PermissionDefault;

/** Part-one utilities. Resolve permissions before dispatch; inspect and mutate on the target thread. */
final class PlayerCommands implements Listener, TaskHandle {
    private volatile boolean cancelled;

    @Override public void cancel() { cancelled = true; }
    @Override public boolean isCancelled() { return cancelled; }
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PlayerConfig> config;
    private final PlayerStore store;

    PlayerCommands(ModuleContext ctx, ConfigHandle<PlayerConfig> config, PlayerStore store) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.store = store;
    }

    void register() {
        for (String name : List.of("feed", "god", "gamemode", "speed", "hat", "repair",
                "extinguish", "suicide", "burn", "lightning", "exp")) {
            ctx.permission("atrium." + name, PermissionDefault.OP,
                    "Use /" + name);
            ctx.permission("atrium." + name + ".others", PermissionDefault.OP,
                    "Use /" + name + " on other players");
        }
        for (GameMode mode : GameMode.values()) {
            ctx.permission(modePermission(mode), PermissionDefault.OP, "Use gamemode " + mode.name());
        }
        ctx.permission("atrium.repair.all", PermissionDefault.OP, "Repair all inventory slots");
        ctx.listen(this);
        // Track one lifecycle guard: core task tracking is not safe for concurrent per-command additions.
        ctx.track(this);
        simple("feed", (s, p) -> {
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setExhaustion(0f);
            done(s, p, "fed");
        });
        simple("god", (s, p) -> {
            var state = ctx.users().user(p).update(store.component, old -> old.withGod(!old.god()));
            done(s, p, state.god() ? "god-enabled" : "god-disabled");
        });
        simple("extinguish", (s, p) -> {
            p.setFireTicks(0);
            done(s, p, "extinguished");
        });
        simple("suicide", (s, p) -> {
            if (p.isDead()) { reply(s, "already-dead"); return; }
            p.setHealth(0);
            if (!p.isDead()) { reply(s, "cancelled"); return; }
            done(s, p, "suicide");
        });
        simple("hat", this::hat);
        simple("lightning", (s, p) -> {
            var location = p.getLocation();
            String name = p.getName();
            boolean effect = config.get().lightningEffectOnly;
            ctx.scheduler().region(location, () -> {
                if (cancelled) return;
                try {
                    if (effect) location.getWorld().strikeLightningEffect(location);
                    else location.getWorld().strikeLightning(location);
                    reply(p, "lightning");
                    if (!s.equals(p)) reply(s, "lightning-other", Msg.unparsed("player", name));
                } catch (RuntimeException e) {
                    ctx.logger().error("Lightning at {} failed", location, e);
                    reply(s, "failed");
                }
            });
        });
        registerGamemode();
        registerSpeed();
        registerRepair();
        registerBurn();
        registerExp();
    }

    private void simple(String name, BiConsumer<CommandSender, Player> action) {
        Command<CommandSourceStack> command = c -> run(c, name, action);
        register(withTarget(Cmds.literal(name, "atrium." + name), command));
    }

    private LiteralArgumentBuilder<CommandSourceStack> withTarget(
            LiteralArgumentBuilder<CommandSourceStack> node, Command<CommandSourceStack> command) {
        return node.executes(command).then(Commands.argument("target", ArgumentTypes.player()).executes(command));
    }

    private void register(LiteralArgumentBuilder<CommandSourceStack> node) {
        ctx.command(CommandSpec.of(node.build(), CommandDescriptions.of(node.getLiteral())));
    }

    private int usage(CommandContext<CommandSourceStack> c, String command) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "player.usage." + command);
    }

    private int run(CommandContext<CommandSourceStack> c, String permission,
            BiConsumer<CommandSender, Player> action) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", "atrium." + permission + ".others");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            if (cancelled) return;
            try {
                action.accept(sender, target);
            } catch (RuntimeException e) {
                ctx.logger().error("Player command {} failed for {}", permission, target.getUniqueId(), e);
                reply(sender, "failed");
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    // Rendering reads the recipient's locale, so even feedback is sent on their own thread.
    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) return;
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) ctx.messages().send(player, "player." + key, tags);
            }, null);
        } else {
            ctx.messages().send(recipient, "player." + key, tags);
        }
    }

    private void done(CommandSender sender, Player target, String key, TagResolver... tags) {
        reply(target, key, tags);
        if (!sender.equals(target)) {
            reply(sender, key + "-other", TagResolver.resolver(
                    Msg.unparsed("player", target.getName()), TagResolver.resolver(tags)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && ctx.users().user(player).get(store.component).god()) {
            event.setCancelled(true);
        }
    }

    private static String modePermission(GameMode mode) {
        return "atrium.gamemode." + mode.name().toLowerCase(Locale.ROOT);
    }

    private void registerGamemode() {
        Command<CommandSourceStack> command = c -> {
            GameMode mode;
            try { mode = PlayerValues.mode(StringArgumentType.getString(c, "mode")); }
            catch (IllegalArgumentException e) { throw cmds.fail(c.getSource(), "player.invalid-mode"); }
            return gamemode(c, mode);
        };
        register(Cmds.literal("gamemode", "atrium.gamemode")
                .executes(c -> usage(c, "gamemode"))
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("survival", "creative", "adventure", "spectator")))
                        .executes(command)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(command))));
        for (String alias : List.of("gmc", "gms", "gma", "gmsp")) {
            GameMode mode = PlayerValues.mode(alias);
            register(withTarget(Cmds.literal(alias, "atrium.gamemode")
                    .requires(source -> source.getSender().hasPermission("atrium.gamemode")
                            && source.getSender().hasPermission(modePermission(mode))), c -> gamemode(c, mode)));
        }
    }

    private int gamemode(CommandContext<CommandSourceStack> c, GameMode mode) throws CommandSyntaxException {
        if (!c.getSource().getSender().hasPermission(modePermission(mode)))
            throw cmds.fail(c.getSource(), "player.mode-denied");
        return run(c, "gamemode", (s, p) -> {
            p.setGameMode(mode); // Paper fires its cancellable gamemode event.
            if (p.getGameMode() != mode) { reply(s, "cancelled"); return; }
            done(s, p, "gamemode", Msg.unparsed("mode", mode.name().toLowerCase(Locale.ROOT)));
        });
    }

    private void registerSpeed() {
        Command<CommandSourceStack> automatic = c -> speed(c, null);
        var value = Commands.argument("value", IntegerArgumentType.integer(0, 10))
                .suggests(Cmds.suggest(() -> List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")))
                .executes(automatic)
                .then(Commands.argument("target", ArgumentTypes.player()).executes(automatic));
        for (String kind : List.of("walk", "fly"))
            value.then(withTarget(Commands.literal(kind), c -> speed(c, kind)));
        register(Cmds.literal("speed", "atrium.speed").executes(c -> usage(c, "speed")).then(value));
    }

    private int speed(CommandContext<CommandSourceStack> c, String kind) throws CommandSyntaxException {
        int value = IntegerArgumentType.getInteger(c, "value");
        return run(c, "speed", (s, p) -> {
            boolean fly = kind == null ? p.isFlying() : kind.equals("fly");
            if (fly) p.setFlySpeed(PlayerValues.speed(value));
            else p.setWalkSpeed(PlayerValues.speed(value));
            done(s, p, "speed", Msg.number("speed", value), Msg.unparsed("kind", fly ? "fly" : "walk"));
        });
    }

    private void hat(CommandSender sender, Player player) {
        var inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        ItemStack helmet = inventory.getHelmet();
        if (hand.isEmpty() || hand.getAmount() != 1) { reply(sender, "hat-single"); return; }
        if (helmet != null && helmet.containsEnchantment(Enchantment.BINDING_CURSE)
                && player.getGameMode() != GameMode.CREATIVE) { reply(sender, "hat-bound"); return; }
        // Swap complete stacks in one entity task; no drops, spare slot, or delayed snapshot.
        inventory.setItemInMainHand(helmet);
        inventory.setHelmet(hand);
        done(sender, player, "hat");
    }

    private void registerRepair() {
        register(withTarget(Cmds.literal("repair", "atrium.repair"), c -> repair(c, false))
                .then(withTarget(Commands.literal("hand"), c -> repair(c, false)))
                .then(withTarget(Cmds.literal("all").requires(Cmds.permission("atrium.repair.all")), c -> repair(c, true))));
    }

    private int repair(CommandContext<CommandSourceStack> c, boolean all) throws CommandSyntaxException {
        return run(c, "repair", (s, p) -> {
            var inventory = p.getInventory();
            int count = 0;
            if (all) {
                for (int slot = 0; slot < inventory.getSize(); slot++)
                    if (repairItem(inventory.getItem(slot))) count++;
            } else if (repairItem(inventory.getItem(inventory.getHeldItemSlot()))) count++;
            if (count == 0) { reply(s, "repair-none"); return; }
            done(s, p, "repair", Msg.number("count", count));
        });
    }

    private static boolean repairItem(ItemStack item) {
        if (item == null || item.isEmpty() || !(item.getItemMeta() instanceof Damageable meta) || !meta.hasDamage())
            return false;
        meta.setDamage(0);
        // Mutate the actual slot stack. Never replace it with a cloned ItemStack.
        return item.setItemMeta(meta);
    }

    private void registerBurn() {
        register(Cmds.literal("burn", "atrium.burn")
                .executes(c -> usage(c, "burn"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(c -> usage(c, "burn"))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, Integer.MAX_VALUE / 20))
                                .suggests(Cmds.suggest(() -> List.of("5", "10", "30", "60")))
                                .executes(c -> {
                                    int seconds = IntegerArgumentType.getInteger(c, "seconds");
                                    int max = Math.clamp(config.get().maxBurnSeconds, 1, Integer.MAX_VALUE / 20);
                                    if (seconds > max) throw cmds.fail(c.getSource(), "player.burn-limit", Msg.number("max", max));
                                    return run(c, "burn", (s, p) -> {
                                        p.setFireTicks(seconds * 20);
                                        done(s, p, "burn", Msg.number("seconds", seconds));
                                    });
                                }))));
    }

    private void registerExp() {
        var root = Cmds.literal("exp", "atrium.exp").executes(c -> usage(c, "exp"));
        for (String operation : List.of("give", "set", "take", "show")) {
            var target = Commands.argument("target", ArgumentTypes.player());
            if (operation.equals("show")) target.executes(c -> exp(c, operation, null));
            else target.executes(c -> usage(c, "exp"));
            target.then(Commands.argument("amount", StringArgumentType.word())
                    .suggests(Cmds.suggest(() -> List.of("10", "100", "1000", "1L", "5L", "30L"))).executes(c -> {
                PlayerValues.ExpAmount amount;
                try { amount = PlayerValues.exp(StringArgumentType.getString(c, "amount")); }
                catch (IllegalArgumentException e) { throw cmds.fail(c.getSource(), "player.invalid-exp"); }
                return exp(c, operation, amount);
            }));
            root.then(Commands.literal(operation).executes(c -> usage(c, "exp")).then(target));
        }
        register(root);
    }

    private int exp(CommandContext<CommandSourceStack> c, String operation, PlayerValues.ExpAmount amount)
            throws CommandSyntaxException {
        return run(c, "exp", (s, p) -> {
            if (operation.equals("show")) {
                reply(s, "exp-show", Msg.unparsed("player", p.getName()), Msg.number("levels", p.getLevel()),
                        Msg.number("points", p.calculateTotalExperiencePoints()));
                return;
            }
            int current = amount.levels() ? p.getLevel() : p.calculateTotalExperiencePoints();
            long next = switch (operation) {
                case "give" -> (long) current + amount.value();
                case "take" -> Math.max(0L, (long) current - amount.value());
                default -> amount.value();
            };
            // Keep levels within the representable point range, including fractional progress.
            if (next > Integer.MAX_VALUE || (amount.levels() && next > 21862)) {
                reply(s, "exp-overflow"); return;
            }
            if (amount.levels()) {
                p.setLevel((int) next);
                if (operation.equals("set") || (operation.equals("take") && amount.value() > current)) p.setExp(0);
            } else {
                p.setExperienceLevelAndProgress((int) next);
            }
            p.setTotalExperience(p.calculateTotalExperiencePoints());
            done(s, p, "exp-changed", Msg.number("levels", p.getLevel()), Msg.number("points", p.calculateTotalExperiencePoints()));
        });
    }
}
