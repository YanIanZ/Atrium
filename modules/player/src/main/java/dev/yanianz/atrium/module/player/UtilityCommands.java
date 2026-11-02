package dev.yanianz.atrium.module.player;

import com.mojang.brigadier.arguments.DoubleArgumentType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.Vector;

/**
 * /falldistance, /tgod, /sound, /smite, /solve and /shoot.
 *
 * <p>Commands that touch a player run on that player's thread. Timed god keeps at most one timer per
 * player and compares the recorded deadline before disabling, so a repeated command extends rather than
 * fights the timer.
 */
final class UtilityCommands implements Listener, TaskHandle {
    private static volatile List<String> soundNames;

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PlayerConfig> config;
    private final PlayerStore store;
    private final Map<UUID, TimedGod> timedGod = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    private record TimedGod(TaskHandle task, long expiresAt, boolean owned) { }

    UtilityCommands(ModuleContext ctx, ConfigHandle<PlayerConfig> config, PlayerStore store) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.store = store;
    }

    void register() {
        ctx.track(this);
        ctx.listen(this);
        for (String name : List.of("falldistance", "tgod", "sound", "smite", "shoot")) {
            ctx.permission("atrium." + name, PermissionDefault.OP, "Use /" + name);
            ctx.permission("atrium." + name + ".others", PermissionDefault.OP,
                    "Use /" + name + " on other players");
        }
        ctx.permission("atrium.solve", PermissionDefault.TRUE, "Use /solve");
        ctx.permission("atrium.sound.all", PermissionDefault.OP, "Play a sound for every online player");

        registerFallDistance();
        registerTimedGod();
        registerSound();
        registerSmite();
        registerSolve();
        registerShoot();
    }

    private void register(LiteralArgumentBuilder<CommandSourceStack> node) {
        ctx.command(CommandSpec.of(node.build(), CommandDescriptions.of(node.getLiteral())));
    }

    private int usage(CommandContext<CommandSourceStack> c, String command) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "player.usage." + command);
    }

    // ---- /falldistance ----

    private void registerFallDistance() {
        register(Cmds.literal("falldistance", "atrium.falldistance")
                .executes(c -> usage(c, "falldistance"))
                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0, UtilityValues.MAX_FALL_DISTANCE))
                        .executes(this::fallDistance)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::fallDistance))));
    }

    private int fallDistance(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        float distance;
        try {
            distance = UtilityValues.fallDistance(DoubleArgumentType.getDouble(c, "distance"));
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "player.invalid-fall-distance");
        }
        return run(c, "falldistance", (sender, target) -> {
            target.setFallDistance(distance);
            done(sender, target, "falldistance", Msg.number("distance", distance));
        });
    }

    // ---- /tgod ----

    private void registerTimedGod() {
        register(Cmds.literal("tgod", "atrium.tgod")
                .executes(c -> usage(c, "tgod"))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 31_536_000))
                        .suggests(Cmds.suggest(() -> List.of("30", "60", "300", "3600")))
                        .executes(this::timedGod)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::timedGod))));
    }

    private int timedGod(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int seconds = IntegerArgumentType.getInteger(c, "seconds");
        int max = Math.clamp(config.get().maxTimedGodSeconds, 1, 31_536_000);
        if (seconds > max) {
            throw cmds.fail(c.getSource(), "player.tgod-limit", Msg.number("max", max));
        }
        Player target = cmds.selfOrOther(c, "target", "atrium.tgod.others");
        CommandSender sender = c.getSource().getSender();
        long expiresAt = System.currentTimeMillis() + seconds * 1000L;
        ctx.scheduler().entity(target, () -> {
            if (cancelled) {
                return;
            }
            try {
                if (!target.isOnline()) {
                    reply(sender, "target-left");
                    return;
                }
                boolean wasGod = ctx.users().user(target).get(store.component).god();
                ctx.users().user(target).update(store.component, state -> state.withGod(true));
                TimedGod previous = timedGod.remove(target.getUniqueId());
                if (previous != null) {
                    previous.task().cancel();
                }
                // Only disable god on expiry when timed god enabled it; a pre-existing /god is preserved.
                boolean owned = previous != null ? previous.owned() : !wasGod;
                TaskHandle task = ctx.track(ctx.scheduler()
                        .globalLater(() -> expireTimedGod(target.getUniqueId(), expiresAt), seconds * 20L));
                timedGod.put(target.getUniqueId(), new TimedGod(task, expiresAt, owned));
                done(sender, target, "tgod-enabled", Msg.number("seconds", seconds));
            } catch (RuntimeException e) {
                ctx.logger().error("Timed god for {} failed", target.getUniqueId(), e);
                reply(sender, "failed");
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private void expireTimedGod(UUID uuid, long expiresAt) {
        TimedGod entry = timedGod.get(uuid);
        if (entry == null || entry.expiresAt() != expiresAt) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            timedGod.remove(uuid, entry);
            return;
        }
        ctx.scheduler().entity(player, () -> {
            if (cancelled || timedGod.get(uuid) != entry) {
                return;
            }
            timedGod.remove(uuid, entry);
            if (!player.isOnline()) {
                return;
            }
            try {
                if (entry.owned() && ctx.users().user(player).get(store.component).god()) {
                    ctx.users().user(player).update(store.component, state -> state.withGod(false));
                    ctx.messages().send(player, "player.tgod-expired");
                }
            } catch (RuntimeException e) {
                ctx.logger().error("Expiring timed god for {} failed", uuid, e);
            }
        }, null);
    }

    // ---- /sound ----

    private void registerSound() {
        var pitch = Commands.argument("pitch", DoubleArgumentType.doubleArg(0.5, 2.0))
                .executes(this::sound);
        var volume = Commands.argument("volume", DoubleArgumentType.doubleArg(0, UtilityValues.MAX_SOUND_VOLUME))
                .executes(this::sound)
                .then(pitch);
        var target = Commands.argument("target", StringArgumentType.word())
                .suggests(Cmds.suggest(UtilityCommands::soundTargets))
                .executes(this::sound)
                .then(volume);
        register(Cmds.literal("sound", "atrium.sound")
                .executes(c -> usage(c, "sound"))
                .then(Commands.argument("sound", StringArgumentType.word())
                        .suggests(Cmds.suggest(UtilityCommands::soundNames))
                        .executes(this::sound)
                        .then(target)));
    }

    private int sound(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        UtilityValues.SoundInput input;
        try {
            input = UtilityValues.sound(StringArgumentType.getString(c, "sound"));
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "player.sound-invalid");
        }
        Sound sound = resolveSound(input);
        if (sound == null) {
            throw cmds.fail(c.getSource(), "player.sound-unknown", Msg.unparsed("sound", input.value()));
        }
        double volume = Cmds.hasArgument(c, "volume") ? DoubleArgumentType.getDouble(c, "volume") : 1.0;
        double pitch = Cmds.hasArgument(c, "pitch") ? DoubleArgumentType.getDouble(c, "pitch") : 1.0;
        float soundVolume = (float) volume;
        float soundPitch = (float) pitch;

        List<Player> recipients;
        String targetName = Cmds.hasArgument(c, "target") ? StringArgumentType.getString(c, "target") : null;
        if (targetName == null) {
            recipients = List.of(cmds.player(c.getSource()));
        } else if (targetName.equalsIgnoreCase("all")) {
            if (!sender.hasPermission("atrium.sound.all")) {
                throw cmds.fail(c.getSource(), "player.sound-no-all");
            }
            recipients = List.copyOf(Bukkit.getOnlinePlayers());
        } else {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                throw cmds.fail(c.getSource(), "core.player-not-found");
            }
            if (!target.equals(sender) && !sender.hasPermission("atrium.sound.others")) {
                throw cmds.fail(c.getSource(), "core.no-permission-others",
                        Msg.unparsed("permission", "atrium.sound.others"));
            }
            recipients = List.of(target);
        }
        if (recipients.isEmpty()) {
            throw cmds.fail(c.getSource(), "core.player-not-found");
        }
        List<Player> audience = List.copyOf(recipients);
        for (Player player : audience) {
            ctx.scheduler().entity(player, () -> {
                if (cancelled || !player.isOnline()) {
                    return;
                }
                Location at = player.getLocation();
                player.playSound(at, sound, SoundCategory.MASTER, soundVolume, soundPitch);
            }, null);
        }
        reply(sender, "sound-played", Msg.unparsed("sound", input.value()),
                Msg.number("count", audience.size()));
        return Cmds.OK;
    }

    @SuppressWarnings("removal")
    private static Sound resolveSound(UtilityValues.SoundInput input) {
        try {
            if (input.namespaced()) {
                NamespacedKey key = NamespacedKey.fromString(input.value());
                Sound sound = key == null ? null : Registry.SOUND_EVENT.get(key);
                if (sound != null) {
                    return sound;
                }
            }
            // A lower-case constant still resolves, so "block_note_block_pling" keeps working.
            return Sound.valueOf(input.value().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> soundNames() {
        List<String> cached = soundNames;
        if (cached != null) {
            return cached;
        }
        try {
            List<String> names = Registry.SOUND_EVENT.keyStream().map(NamespacedKey::getKey).toList();
            soundNames = List.copyOf(names);
            return soundNames;
        } catch (RuntimeException e) {
            return List.of("entity.player.levelup", "block.note_block.pling", "entity.experience_orb.pickup");
        }
    }

    private static List<String> soundTargets() {
        List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        names.add("all");
        return names;
    }

    // ---- /smite ----

    private void registerSmite() {
        var root = Cmds.literal("smite", "atrium.smite").executes(c -> smite(c, false));
        root.then(Commands.literal("safe").executes(c -> smite(c, true))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(c -> smite(c, true))));
        root.then(Commands.argument("target", ArgumentTypes.player())
                .executes(c -> smite(c, false))
                .then(Commands.literal("safe").executes(c -> smite(c, true))));
        register(root);
    }

    private int smite(CommandContext<CommandSourceStack> c, boolean safe) throws CommandSyntaxException {
        return run(c, "smite", (sender, target) -> {
            Location at = target.getLocation();
            if (safe) {
                at.getWorld().strikeLightningEffect(at);
            } else {
                at.getWorld().strikeLightning(at);
            }
            done(sender, target, safe ? "smite-safe" : "smite", Msg.unparsed("player", target.getName()));
        });
    }

    // ---- /solve ----

    private void registerSolve() {
        register(Cmds.literal("solve", "atrium.solve")
                .executes(c -> usage(c, "solve"))
                .then(Commands.argument("expression", StringArgumentType.greedyString())
                        .executes(this::solve)));
    }

    private int solve(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String expression = StringArgumentType.getString(c, "expression");
        double value;
        try {
            value = MathSolver.evaluate(expression);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "player.solve-invalid");
        }
        reply(c.getSource().getSender(), "solve-result", Msg.unparsed("expression", expression),
                Msg.unparsed("result", MathSolver.format(value)));
        return Cmds.OK;
    }

    // ---- /shoot ----

    private void registerShoot() {
        register(Cmds.literal("shoot", "atrium.shoot")
                .executes(c -> usage(c, "shoot"))
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> UtilityValues.PROJECTILES))
                        .executes(c -> shoot(c, 1.5))
                        .then(Commands.argument("speed",
                                        DoubleArgumentType.doubleArg(0, UtilityValues.MAX_SHOOT_SPEED))
                                .suggests(Cmds.suggest(() -> List.of("0", "0.5", "1", "1.5", "2", "3", "5")))
                                .executes(c -> shoot(c, DoubleArgumentType.getDouble(c, "speed")))
                                .then(Commands.argument("target", ArgumentTypes.player())
                                        .executes(c -> shoot(c, DoubleArgumentType.getDouble(c, "speed")))))));
    }

    private int shoot(CommandContext<CommandSourceStack> c, double speed) throws CommandSyntaxException {
        double velocity = UtilityValues.shootSpeed(speed);
        Player player = cmds.player(c.getSource());
        CommandSender sender = c.getSource().getSender();
        Player target = Cmds.hasArgument(c, "target") ? cmds.argPlayer(c, "target") : null;
        if (target != null && !target.equals(sender) && !sender.hasPermission("atrium.shoot.others")) {
            throw cmds.fail(c.getSource(), "core.no-permission-others",
                    Msg.unparsed("permission", "atrium.shoot.others"));
        }
        String raw = StringArgumentType.getString(c, "type").strip().toLowerCase(Locale.ROOT);
        String key = raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        EntityType type = namespaced == null ? null : Registry.ENTITY_TYPE.get(namespaced);
        if (type == null || type.getEntityClass() == null || !Projectile.class.isAssignableFrom(type.getEntityClass())) {
            throw cmds.fail(c.getSource(), "player.shoot-invalid", Msg.unparsed("type", raw));
        }
        ctx.scheduler().entity(player, () -> {
            if (cancelled || !player.isOnline()) {
                return;
            }
            try {
                Location eye = player.getEyeLocation();
                Vector direction = eye.getDirection().normalize();
                Entity spawned = eye.getWorld().spawnEntity(eye.clone().add(direction.clone().multiply(0.6)), type);
                if (!(spawned instanceof Projectile projectile)) {
                    spawned.remove();
                    reply(sender, "shoot-invalid", Msg.unparsed("type", raw));
                    return;
                }
                projectile.setShooter(player);
                projectile.setVelocity(direction.multiply(velocity));
                if (spawned instanceof ShulkerBullet bullet && target != null && target.isOnline()) {
                    bullet.setTarget(target);
                }
                reply(sender, "shoot", Msg.unparsed("type", raw), Msg.number("count", 1));
            } catch (RuntimeException e) {
                ctx.logger().error("Shooting {} for {} failed", raw, player.getUniqueId(), e);
                reply(sender, "failed");
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    // ---- shared helpers ----

    private int run(CommandContext<CommandSourceStack> c, String permission,
            BiConsumer<CommandSender, Player> action) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", "atrium." + permission + ".others");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            if (cancelled) {
                return;
            }
            try {
                action.accept(sender, target);
            } catch (RuntimeException e) {
                ctx.logger().error("Utility command {} failed for {}", permission, target.getUniqueId(), e);
                reply(sender, "failed");
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    ctx.messages().send(player, "player." + key, tags);
                }
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        TimedGod entry = timedGod.remove(event.getPlayer().getUniqueId());
        if (entry != null) {
            entry.task().cancel();
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        for (TimedGod entry : timedGod.values()) {
            entry.task().cancel();
        }
        timedGod.clear();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
