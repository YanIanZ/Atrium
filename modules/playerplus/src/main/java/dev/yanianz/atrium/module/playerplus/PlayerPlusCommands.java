package dev.yanianz.atrium.module.playerplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/** Every command of the module. Success and failure both answer; usage errors are Brigadier's. */
final class PlayerPlusCommands {
    static final String PERM_TFLY = "atrium.playerplus.tfly";
    static final String PERM_FLIGHTCHARGE = "atrium.playerplus.flightcharge";
    static final String PERM_FLIGHTCHARGE_ADMIN = "atrium.playerplus.flightcharge.admin";
    static final String PERM_CUFF = "atrium.playerplus.cuff";
    static final String PERM_CUFF_EXEMPT = "atrium.playerplus.cuff.exempt";
    static final String PERM_GIFTPET = "atrium.playerplus.giftpet";
    static final String PERM_HUNGER = "atrium.playerplus.hunger";
    static final String PERM_SATURATION = "atrium.playerplus.saturation";
    static final String PERM_SATURATION_OTHERS = "atrium.playerplus.saturation.others";
    static final String PERM_SILENT = "atrium.playerplus.silent";

    private static final long MAX_COMMAND_AMOUNT = 86_400_000L;

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PlayerPlusConfig> config;
    private final PlayerPlusStore store;
    private final TflyManager tfly;
    private final FlightChargeManager charge;
    private final CuffManager cuff;
    private final GiftPetManager giftPet;

    PlayerPlusCommands(ModuleContext ctx, ConfigHandle<PlayerPlusConfig> config, PlayerPlusStore store,
            TflyManager tfly, FlightChargeManager charge, CuffManager cuff, GiftPetManager giftPet) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.store = store;
        this.tfly = tfly;
        this.charge = charge;
        this.cuff = cuff;
        this.giftPet = giftPet;
    }

    void register() {
        ctx.permission(PERM_TFLY, PermissionDefault.OP, "Give temporary flight");
        ctx.permission(PERM_FLIGHTCHARGE, PermissionDefault.TRUE,
                "Buy and use flight charge (/flightcharge, /flyc, /recharge, /autorecharge)");
        ctx.permission(PERM_FLIGHTCHARGE_ADMIN, PermissionDefault.OP, "Change the flight charge of players");
        ctx.permission(PERM_CUFF, PermissionDefault.OP, "Cuff and uncuff players");
        ctx.permission(PERM_CUFF_EXEMPT, PermissionDefault.FALSE, "Cannot be cuffed");
        ctx.permission(PERM_GIFTPET, PermissionDefault.TRUE, "Give one of your tamed pets to another player");
        ctx.permission(PERM_HUNGER, PermissionDefault.OP, "Change the hunger of players");
        ctx.permission(PERM_SATURATION, PermissionDefault.OP, "Change saturation");
        ctx.permission(PERM_SATURATION_OTHERS, PermissionDefault.OP, "Change saturation of other players");
        ctx.permission(PERM_SILENT, PermissionDefault.OP, "Use -s to keep the target from being notified");

        registerTfly();
        registerFlightCharge();
        registerCuff();
        registerGiftPet();
        registerHunger();
        registerSaturation();
    }

    // ---- /tfly ----

    private void registerTfly() {
        var duration = Commands.argument("duration", StringArgumentType.word())
                .suggests(Cmds.suggest(() -> List.of("30s", "1m", "5m", "10m", "30m", "1h", "2h", "1d")))
                .executes(this::tfly);
        duration.then(silentNode(this::tfly));
        var off = Cmds.literal("off").executes(this::tflyOff);
        off.then(silentNode(this::tflyOff));
        ctx.command(CommandSpec.of(Cmds.literal("tfly", PERM_TFLY)
                .then(Commands.argument("player", ArgumentTypes.player())
                        .then(duration)
                        .then(off))
                .build(), "Give a player temporary flight for a duration, or end it", "tempfly"));
    }

    private int tfly(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean silent = silent(c);
        Player target = cmds.argPlayer(c, "player");
        Duration duration = parseDuration(c, StringArgumentType.getString(c, "duration"));
        long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException e) {
            throw cmds.fail(c.getSource(), "playerplus.invalid-duration");
        }
        long max = tfly.maxMillis();
        if (millis <= 0 || millis > max) {
            throw cmds.fail(c.getSource(), "playerplus.tfly-limit",
                    Msg.number("max", Math.max(1, max / 1000)));
        }
        if (!tfly.available()) {
            throw cmds.fail(c.getSource(), "playerplus.no-flight-service");
        }
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            long total = tfly.extend(target, millis);
            if (!silent || target.equals(sender)) {
                ctx.messages().send(target, "playerplus.tfly-started", Msg.duration("time", Duration.ofMillis(total)));
            }
            if (!target.equals(sender)) {
                reply(sender, "tfly-started-other", Msg.unparsed("player", target.getName()),
                        Msg.duration("time", Duration.ofMillis(total)));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private int tflyOff(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean silent = silent(c);
        Player target = cmds.argPlayer(c, "player");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            tfly.stop(target, true);
            if (!silent || target.equals(sender)) {
                ctx.messages().send(target, "playerplus.tfly-stopped");
            }
            if (!target.equals(sender)) {
                reply(sender, "tfly-stopped-other", Msg.unparsed("player", target.getName()));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private Duration parseDuration(CommandContext<CommandSourceStack> c, String raw)
            throws CommandSyntaxException {
        Optional<Duration> parsed = Durations.parse(raw);
        if (parsed.isEmpty() || parsed.get().isZero() || parsed.get().isNegative()) {
            throw cmds.fail(c.getSource(), "core.invalid-duration", Msg.unparsed("input", raw));
        }
        return parsed.get();
    }

    // ---- /flightcharge, /flyc, /recharge, /autorecharge ----

    private void registerFlightCharge() {
        var amount = Commands.argument("amount", LongArgumentType.longArg(1, MAX_COMMAND_AMOUNT))
                .suggests(Cmds.suggest(() -> List.of("60", "300", "600", "1800")));
        amount.executes(c -> buy(c, LongArgumentType.getLong(c, "amount"),
                config.get().flightCharge.currency));
        amount.then(Cmds.literal("money").executes(c -> buy(c,
                LongArgumentType.getLong(c, "amount"), PlayerPlusConfig.Currency.MONEY)));
        amount.then(Cmds.literal("exp").executes(c -> buy(c,
                LongArgumentType.getLong(c, "amount"), PlayerPlusConfig.Currency.EXP)));
        var buy = Cmds.literal("buy")
                .executes(c -> buy(c, Math.max(1, config.get().flightCharge.defaultPurchaseSeconds),
                        config.get().flightCharge.currency))
                .then(amount);

        var root = Cmds.literal("flightcharge", PERM_FLIGHTCHARGE)
                .executes(this::chargeStatus)
                .then(Cmds.literal("show").executes(this::chargeStatus))
                .then(buy);
        root.then(adminChargeNode("add"));
        root.then(adminChargeNode("take"));
        root.then(adminChargeNode("set"));
        ctx.command(CommandSpec.of(root.build(),
                "Show your flight charge balance or buy more charge", "flycharge"));

        var rechargeAmount = Commands.argument("amount", LongArgumentType.longArg(1, MAX_COMMAND_AMOUNT))
                .suggests(Cmds.suggest(() -> List.of("60", "300", "600", "1800")));
        ctx.command(CommandSpec.of(Cmds.literal("recharge", PERM_FLIGHTCHARGE)
                .executes(c -> buy(c, Math.max(1, config.get().flightCharge.defaultPurchaseSeconds),
                        config.get().flightCharge.currency))
                .then(rechargeAmount.executes(c -> buy(c, LongArgumentType.getLong(c, "amount"),
                        config.get().flightCharge.currency)))
                .build(), "Buy flight charge with money or experience points"));

        ctx.command(CommandSpec.of(Cmds.literal("flyc", PERM_FLIGHTCHARGE)
                .executes(this::toggleChargedFlight)
                .build(), "Toggle flight paid for by your flight charge"));

        ctx.command(CommandSpec.of(Cmds.literal("autorecharge", PERM_FLIGHTCHARGE)
                .executes(this::autoRechargeStatus)
                .then(Cmds.literal("on").executes(c -> setAutoRecharge(c, true)))
                .then(Cmds.literal("off").executes(c -> setAutoRecharge(c, false)))
                .build(), "Show or set automatic flight-charge top-up"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> adminChargeNode(String operation) {
        var target = Commands.argument("target", ArgumentTypes.player())
                .executes(c -> adminCharge(c, operation));
        target.then(silentNode(c -> adminCharge(c, operation)));
        var amount = Commands.argument("amount", LongArgumentType.longArg(0, MAX_COMMAND_AMOUNT))
                .suggests(Cmds.suggest(() -> List.of("0", "60", "300", "600", "1800")))
                .executes(c -> adminCharge(c, operation))
                .then(target);
        amount.then(silentNode(c -> adminCharge(c, operation)));
        return Cmds.literal(operation)
                .requires(Cmds.permission(PERM_FLIGHTCHARGE_ADMIN))
                .then(amount);
    }

    private int buy(CommandContext<CommandSourceStack> c, long amount, PlayerPlusConfig.Currency currency)
            throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (!charge.enabled()) {
            throw cmds.fail(c.getSource(), "playerplus.charge-disabled");
        }
        charge.purchase(player, Math.max(1, amount), currency, false);
        return Cmds.OK;
    }

    private int toggleChargedFlight(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> charge.toggle(player), null);
        return Cmds.OK;
    }

    private int chargeStatus(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                PlayerPlusState state = ctx.users().user(player).get(store.component);
                ctx.messages().send(player, "playerplus.charge-status",
                        Msg.number("charge", Math.min(state.flightChargeSeconds(), charge.maxSeconds())),
                        Msg.number("max", charge.maxSeconds()),
                        Msg.unparsed("active", charge.active(player) ? "on" : "off"),
                        Msg.unparsed("auto", state.autoRecharge() ? "on" : "off"),
                        Msg.unparsed("price", charge.priceLabel()));
            }, null);
        } else {
            PlayerPlusConfig.FlightCharge cfg = config.get().flightCharge;
            ctx.messages().send(sender, "playerplus.charge-info",
                    Msg.number("max", charge.maxSeconds()),
                    Msg.unparsed("price", charge.priceLabel()),
                    Msg.unparsed("currency", cfg.currency.name().toLowerCase()),
                    Msg.unparsed("auto", cfg.autoRechargeDefault ? "on" : "off"));
        }
        return Cmds.OK;
    }

    private int autoRechargeStatus(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> {
            PlayerPlusState state = ctx.users().user(player).get(store.component);
            ctx.messages().send(player, "playerplus.charge-autorecharge-status",
                    Msg.unparsed("state", state.autoRecharge() ? "on" : "off"),
                    Msg.number("below", config.get().flightCharge.autoRechargeBelowSeconds),
                    Msg.number("amount", Math.max(1, config.get().flightCharge.defaultPurchaseSeconds)));
        }, null);
        return Cmds.OK;
    }

    private int setAutoRecharge(CommandContext<CommandSourceStack> c, boolean value)
            throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> {
            charge.setAutoRecharge(player, value);
            ctx.messages().send(player, "playerplus.charge-autorecharge",
                    Msg.unparsed("state", value ? "on" : "off"));
            if (value) {
                charge.maybeAutoRecharge(player);
            }
        }, null);
        return Cmds.OK;
    }

    private int adminCharge(CommandContext<CommandSourceStack> c, String operation) throws CommandSyntaxException {
        boolean silent = silent(c);
        Player target = Cmds.hasArgument(c, "target") ? cmds.argPlayer(c, "target") : cmds.player(c.getSource());
        long amount = LongArgumentType.getLong(c, "amount");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            // Relative change applied atomically to the stored value (never a stale absolute write).
            charge.changeCharge(target, operation, amount, value -> {
                if (!silent || target.equals(sender)) {
                    tell(target, "charge-admin-changed",
                            Msg.number("charge", value), Msg.number("max", charge.maxSeconds()));
                }
                if (!target.equals(sender)) {
                    reply(sender, "charge-admin-set", Msg.unparsed("player", target.getName()),
                            Msg.number("charge", value), Msg.number("max", charge.maxSeconds()));
                }
            }, error -> reply(sender, "save-failed"));
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    // ---- /cuff, /uncuff ----

    private void registerCuff() {
        var cuffTarget = Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(this::cuff);
        cuffTarget.then(silentNode(this::cuff));
        ctx.command(CommandSpec.of(Cmds.literal("cuff", PERM_CUFF).then(cuffTarget).build(),
                "Confine a player to their current position"));

        var uncuffTarget = Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(this::uncuff);
        uncuffTarget.then(silentNode(this::uncuff));
        ctx.command(CommandSpec.of(Cmds.literal("uncuff", PERM_CUFF).then(uncuffTarget).build(),
                "Release a cuffed player"));
    }

    private int cuff(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean silent = silent(c);
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String by = sender instanceof Player player ? player.getName() : "console";
        var staffId = sender instanceof Player player ? player.getUniqueId() : null;
        ctx.users().resolveAccount(name, false).whenComplete((resolved, error) -> {
            if (error != null) {
                ctx.logger().warn("Resolving {} for /cuff failed", name, error);
                reply(sender, "failed");
                return;
            }
            if (resolved.isEmpty()) {
                reply(sender, "player-not-found", Msg.unparsed("player", name));
                return;
            }
            PlayerRef ref = resolved.get();
            if (staffId != null && staffId.equals(ref.uuid())) {
                reply(sender, "cant-cuff-self");
                return;
            }
            Player online = Bukkit.getPlayer(ref.uuid());
            if (online == null) {
                finishCuff(sender, ref, null, by, silent);
                return;
            }
            ctx.scheduler().supplyOnEntity(online, () -> online.hasPermission(PERM_CUFF_EXEMPT)
                    ? new Capture(true, null)
                    : new Capture(false, CuffPoint.at(online.getLocation(), by)))
                    .whenComplete((capture, captureError) -> {
                        if (captureError != null) {
                            if (captureError instanceof Scheduler.EntityRetiredException) {
                                finishCuff(sender, ref, null, by, silent);
                                return;
                            }
                            ctx.logger().warn("Preparing the cuff of {} failed", ref.name(), captureError);
                            reply(sender, "failed");
                            return;
                        }
                        if (capture.exempt()) {
                            reply(sender, "cuff-exempt", Msg.unparsed("player", ref.name()));
                            return;
                        }
                        finishCuff(sender, ref, capture.point(), by, silent);
                    });
        });
        return Cmds.OK;
    }

    private void finishCuff(CommandSender sender, PlayerRef ref, @Nullable CuffPoint point, String by,
            boolean silent) {
        ctx.users().load(ref.uuid()).whenComplete((loaded, error) -> {
            if (error != null) {
                ctx.logger().warn("Loading {} for /cuff failed", ref.name(), error);
                reply(sender, "failed");
                return;
            }
            if (loaded.isEmpty()) {
                reply(sender, "player-not-found", Msg.unparsed("player", ref.name()));
                return;
            }
            var user = loaded.get();
            CuffPoint stored = point;
            if (stored == null) {
                CuffPoint existing = user.get(store.component).cuff();
                // An offline target has no location yet; the point is fixed where the player joins.
                stored = existing != null ? existing
                        : new CuffPoint("", 0, 0, 0, by, System.currentTimeMillis());
            }
            cuff.apply(user, stored);
            ctx.users().saveNow(user.uuid()).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    ctx.logger().warn("Saving the cuff of {} failed", ref.name(), failure);
                    reply(sender, "save-failed");
                    return;
                }
                reply(sender, "cuff-done", Msg.unparsed("player", ref.name()));
                Player online = Bukkit.getPlayer(user.uuid());
                if (online != null) {
                    ctx.scheduler().entity(online, () -> {
                        if (online.getVehicle() != null) {
                            online.leaveVehicle();
                        }
                        if (!silent) {
                            ctx.messages().send(online, "playerplus.cuff-applied");
                        }
                    }, null);
                }
            });
        });
    }

    private int uncuff(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean silent = silent(c);
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ctx.users().resolveAccount(name, false).whenComplete((resolved, error) -> {
            if (error != null) {
                ctx.logger().warn("Resolving {} for /uncuff failed", name, error);
                reply(sender, "failed");
                return;
            }
            if (resolved.isEmpty()) {
                reply(sender, "player-not-found", Msg.unparsed("player", name));
                return;
            }
            PlayerRef ref = resolved.get();
            ctx.users().load(ref.uuid()).whenComplete((loaded, loadError) -> {
                if (loadError != null) {
                    ctx.logger().warn("Loading {} for /uncuff failed", ref.name(), loadError);
                    reply(sender, "failed");
                    return;
                }
                if (loaded.isEmpty()) {
                    reply(sender, "player-not-found", Msg.unparsed("player", ref.name()));
                    return;
                }
                var user = loaded.get();
                if (!user.get(store.component).cuffed()) {
                    reply(sender, "not-cuffed", Msg.unparsed("player", ref.name()));
                    return;
                }
                cuff.clear(user);
                ctx.users().saveNow(user.uuid()).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        ctx.logger().warn("Saving the uncuff of {} failed", ref.name(), failure);
                        reply(sender, "save-failed");
                        return;
                    }
                    reply(sender, "uncuff-done", Msg.unparsed("player", ref.name()));
                    if (!silent) {
                        Player online = Bukkit.getPlayer(user.uuid());
                        if (online != null) {
                            ctx.scheduler().entity(online,
                                    () -> ctx.messages().send(online, "playerplus.uncuff-applied"), null);
                        }
                    }
                });
            });
        });
        return Cmds.OK;
    }

    // ---- /giftpet ----

    private void registerGiftPet() {
        ctx.command(CommandSpec.of(Cmds.literal("giftpet", PERM_GIFTPET)
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(this::giftPet))
                .build(), "Give one of your tamed pets to another player"));
    }

    private int giftPet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player owner = cmds.player(c.getSource());
        Player target = cmds.argPlayer(c, "target");
        if (owner.equals(target)) {
            throw cmds.fail(c.getSource(), "playerplus.giftpet-self");
        }
        if (!target.isOnline()) {
            throw cmds.fail(c.getSource(), "playerplus.giftpet-target-left",
                    Msg.unparsed("player", target.getName()));
        }
        giftPet.arm(owner, target);
        ctx.messages().send(owner, "playerplus.giftpet-armed",
                Msg.unparsed("player", target.getName()),
                Msg.number("seconds", Math.max(1, config.get().giftPet.windowSeconds)));
        return Cmds.OK;
    }

    // ---- /hunger, /saturation ----

    private void registerHunger() {
        var level = Commands.argument("level",
                        IntegerArgumentType.integer(0, Math.max(1, config.get().food.maxFoodLevel)))
                .suggests(Cmds.suggest(() -> List.of("0", "5", "10", "15", "20")))
                .executes(this::hunger);
        level.then(silentNode(this::hunger));
        ctx.command(CommandSpec.of(Cmds.literal("hunger", PERM_HUNGER)
                .then(Commands.argument("player", ArgumentTypes.player()).then(level))
                .build(), "Set the food level of a player"));
    }

    private int hunger(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        boolean silent = silent(c);
        Player target = cmds.argPlayer(c, "player");
        int level = IntegerArgumentType.getInteger(c, "level");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            target.setFoodLevel(level);
            if (!silent || target.equals(sender)) {
                ctx.messages().send(target, "playerplus.hunger-set", Msg.number("level", level));
            }
            if (!target.equals(sender)) {
                reply(sender, "hunger-set-other", Msg.unparsed("player", target.getName()),
                        Msg.number("level", level));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private void registerSaturation() {
        var target = Commands.argument("target", ArgumentTypes.player())
                .executes(c -> saturation(c, FloatArgumentType.getFloat(c, "value")));
        target.then(silentNode(this::saturationCurrent));
        var value = Commands.argument("value",
                        FloatArgumentType.floatArg(0f, Math.max(0f, config.get().food.maxSaturation)))
                .suggests(Cmds.suggest(() -> List.of("0", "5", "10", "20")))
                .executes(c -> saturation(c, FloatArgumentType.getFloat(c, "value")))
                .then(target);
        value.then(silentNode(this::saturationCurrent));
        ctx.command(CommandSpec.of(Cmds.literal("saturation", PERM_SATURATION)
                .executes(c -> saturation(c, Math.max(0f, config.get().food.maxSaturation)))
                .then(value)
                .build(), "Set the saturation of yourself or another player"));
    }

    private int saturationCurrent(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return saturation(c, FloatArgumentType.getFloat(c, "value"));
    }

    private int saturation(CommandContext<CommandSourceStack> c, float value) throws CommandSyntaxException {
        boolean silent = silent(c);
        CommandSender sender = c.getSource().getSender();
        Player target;
        if (Cmds.hasArgument(c, "target")) {
            target = cmds.argPlayer(c, "target");
            if (!target.equals(sender) && !sender.hasPermission(PERM_SATURATION_OTHERS)) {
                throw cmds.fail(c.getSource(), "core.no-permission-others",
                        Msg.unparsed("permission", PERM_SATURATION_OTHERS));
            }
        } else {
            target = cmds.player(c.getSource());
        }
        float applied = Math.min(value, Math.max(0f, config.get().food.maxSaturation));
        ctx.scheduler().entity(target, () -> {
            target.setSaturation(applied);
            if (!silent || target.equals(sender)) {
                ctx.messages().send(target, "playerplus.saturation-set", Msg.number("saturation", applied));
            }
            if (!target.equals(sender)) {
                reply(sender, "saturation-set-other", Msg.unparsed("player", target.getName()),
                        Msg.number("saturation", applied));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    // ---- helpers ----

    private LiteralArgumentBuilder<CommandSourceStack> silentNode(Command<CommandSourceStack> command) {
        return Cmds.literal("-s")
                .requires(Cmds.permission(PERM_SILENT))
                .executes(command);
    }

    private boolean silent(CommandContext<CommandSourceStack> c) {
        return c.getNodes().stream().anyMatch(node -> node.getNode().getName().equals("-s"));
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        String full = "playerplus." + key;
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, full, tags), null);
        } else {
            ctx.messages().send(sender, full, tags);
        }
    }

    private void tell(Player player, String key, TagResolver... tags) {
        String full = "playerplus." + key;
        ctx.scheduler().entity(player, () -> ctx.messages().send(player, full, tags), null);
    }

    private record Capture(boolean exempt, @Nullable CuffPoint point) {}
}
