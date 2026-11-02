package dev.yanianz.atrium.module.rtp;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.rtp.RandomTeleportService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.rtp.RtpConfig.WorldSettings;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

/**
 * /rtp [world] [player], first-join and respawn random teleports, and the published
 * {@link RandomTeleportService}.
 *
 * <p>Order for a player's own /rtp: permission, world and cooldown checks; search slot; balance pre-check;
 * search (pool or live); payment (only now that a spot exists); teleport through the teleport service
 * (warmup applies); on success the cooldown starts, otherwise the payment is refunded.
 */
final class RtpCommands implements Listener, RandomTeleportService {
    static final String PERM_RTP = "atrium.rtp";
    static final String PERM_OTHERS = "atrium.rtp.others";
    static final String PERM_COOLDOWN_BYPASS = "atrium.rtp.cooldown.bypass";
    static final String PERM_COST_BYPASS = "atrium.rtp.cost.bypass";
    static final String PERM_WORLD_PREFIX = "atrium.rtp.world.";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final ConfigHandle<RtpConfig> config;
    private final RtpSearcher searcher;
    private final RtpCooldowns cooldowns;
    private final RtpStore store;
    private final ExtendedTeleportService teleport;

    RtpCommands(ModuleContext ctx, ConfigHandle<RtpConfig> config, RtpSearcher searcher, RtpCooldowns cooldowns,
            RtpStore store, ExtendedTeleportService teleport) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.config = config;
        this.searcher = searcher;
        this.cooldowns = cooldowns;
        this.store = store;
        this.teleport = teleport;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("rtp", PERM_RTP)
                .executes(this::rtp)
                // Bukkit world names ("world", "world_nether"), not dimension keys; quote names with odd characters.
                .then(Commands.argument("world", StringArgumentType.string())
                        .suggests(Cmds.suggest(() -> Bukkit.getWorlds().stream().map(World::getName).toList()))
                        .executes(this::rtp)
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .requires(Cmds.permission(PERM_OTHERS)).executes(this::rtp)))
                .build(), "Teleport to a random safe location: /rtp [world] [player]", "randomtp", "wild"));
    }

    // ---- /rtp ----

    private int rtp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player target = Cmds.hasArgument(c, "player") ? cmds.selfOrOther(c, "player", PERM_OTHERS) : cmds.player(c.getSource());
        boolean self = target.equals(sender);
        RtpConfig cfg = config.get();
        boolean explicitWorld = Cmds.hasArgument(c, "world");
        // Without a world argument the target is the sender, so reading its world here is on its own thread.
        World world = explicitWorld ? worldArgument(c) : target.getWorld();
        WorldSettings settings = cfg.settings(world.getName(), world.getEnvironment());
        boolean fallback = false;
        if (settings == null && !explicitWorld && cfg.fallbackWorld != null && !cfg.fallbackWorld.isBlank()) {
            World other = Bukkit.getWorld(cfg.fallbackWorld.trim());
            if (other != null) {
                world = other;
                settings = cfg.settings(other.getName(), other.getEnvironment());
                fallback = true;
            }
        }
        if (settings == null) {
            throw cmds.fail(c.getSource(), "rtp.world-disabled", Msg.unparsed("world", world.getName()));
        }
        if (self) {
            if (!cfg.allowOtherWorlds && !fallback && !world.equals(target.getWorld())) {
                throw cmds.fail(c.getSource(), "rtp.other-world-denied");
            }
            if (!worldAllowed(target, world, cfg)) {
                throw cmds.fail(c.getSource(), "rtp.world-no-permission", Msg.unparsed("world", world.getName()));
            }
            if (!target.hasPermission(PERM_COOLDOWN_BYPASS)) {
                Duration left = cooldowns.remaining(target.getUniqueId(), world.getName());
                if (!left.isZero()) {
                    throw cmds.fail(c.getSource(), "rtp.on-cooldown", Msg.unparsed("world", world.getName()),
                            Msg.duration("time", Duration.ofSeconds((left.toMillis() + 999) / 1000)));
                }
            }
        }
        BigDecimal cost = self && !target.hasPermission(PERM_COST_BYPASS) ? price(settings) : BigDecimal.ZERO;
        start(sender, target, world, settings, !self, cost);
        return Cmds.OK;
    }

    private World worldArgument(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "world").trim();
        World world = Bukkit.getWorld(name);
        if (world == null) {
            for (World candidate : Bukkit.getWorlds()) {
                if (candidate.getName().equalsIgnoreCase(name) || candidate.getKey().asString().equalsIgnoreCase(name)) {
                    world = candidate;
                    break;
                }
            }
        }
        if (world == null) {
            throw cmds.fail(c.getSource(), "rtp.unknown-world", Msg.unparsed("world", name));
        }
        return world;
    }

    /**
     * Unset permission: allowed unless the world permission is required. An explicitly set permission
     * (granted or negated by a permission plugin) always decides.
     */
    static boolean worldAllowed(Player player, World world, RtpConfig cfg) {
        String node = PERM_WORLD_PREFIX + world.getName().toLowerCase(Locale.ROOT);
        if (player.isPermissionSet(node)) {
            return player.hasPermission(node);
        }
        return !cfg.requireWorldPermission || player.hasPermission(node);
    }

    private static BigDecimal price(WorldSettings settings) {
        if (!(settings.cost > 0) || !Double.isFinite(settings.cost)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(settings.cost);
    }

    /**
     * Runs a random teleport.
     *
     * @param sender who asked (gets the outcome too when it is not the target), or null for automatic ones
     * @param free   staff or automatic: no warmup, no cooldown
     * @param cost   amount to charge once a spot was found (zero = free)
     */
    private void start(@Nullable CommandSender sender, Player target, World world, WorldSettings settings, boolean free,
            BigDecimal cost) {
        UUID uuid = target.getUniqueId();
        RtpSearcher.Refusal refusal = searcher.reserve(uuid, sender != null);
        if (refusal != null) {
            String key = refusal == RtpSearcher.Refusal.PLAYER_BUSY ? "rtp.busy-player" : "rtp.busy-server";
            messages.send(sender != null ? sender : target, key, Msg.unparsed("player", target.getName()));
            return;
        }
        EconomyService economy = cost.signum() > 0 ? ctx.services().get(EconomyService.class).orElse(null) : null;
        BigDecimal charge = economy == null ? BigDecimal.ZERO : cost;
        TagResolver worldName = Msg.unparsed("world", world.getName());
        CompletableFuture<Boolean> affordable = charge.signum() == 0
                ? CompletableFuture.completedFuture(true)
                : economy.balance(uuid).thenApply(balance -> balance != null && balance.compareTo(charge) >= 0);
        affordable.thenCompose(ok -> {
            if (!ok) {
                messages.send(target, "rtp.insufficient-funds", Msg.unparsed("cost", economy.format(charge)));
                return CompletableFuture.completedFuture(Optional.<Location>empty());
            }
            messages.send(target, "rtp.searching", worldName);
            return searcher.find(world, settings).thenApply(found -> {
                if (found.isEmpty()) {
                    messages.send(target, "rtp.not-found", worldName);
                    if (sender != null && !sender.equals(target)) {
                        messages.send(sender, "rtp.not-found-other", worldName, Msg.unparsed("player", target.getName()));
                    }
                }
                return found;
            });
        }).whenComplete((found, error) -> {
            searcher.release(uuid);
            if (error != null) {
                ctx.logger().error("Random teleport of {} in {} failed", target.getName(), world.getName(), error);
                messages.send(target, "core.error");
                return;
            }
            if (found.isPresent()) {
                payAndTeleport(sender, target, world, settings, free, economy, charge, found.get());
            }
        });
    }

    private void payAndTeleport(@Nullable CommandSender sender, Player target, World world, WorldSettings settings,
            boolean free, @Nullable EconomyService economy, BigDecimal charge, Location destination) {
        UUID uuid = target.getUniqueId();
        String reason = "rtp:" + world.getName();
        CompletableFuture<Boolean> paid = charge.signum() == 0 || economy == null
                ? CompletableFuture.completedFuture(true)
                : economy.withdraw(uuid, charge, reason).handle((result, error) -> {
                    if (error != null) {
                        ctx.logger().error("Charging {} {} for a random teleport failed", target.getName(), charge, error);
                        messages.send(target, "rtp.payment-failed");
                        return false;
                    }
                    if (!result.success()) {
                        messages.send(target, result.status() == TransactionResult.Status.INSUFFICIENT_FUNDS
                                ? "rtp.insufficient-funds" : "rtp.payment-failed", Msg.unparsed("cost", economy.format(charge)));
                        return false;
                    }
                    return true;
                });
        paid.thenAccept(ok -> {
            if (!ok) {
                searcher.giveBack(destination);
                return;
            }
            // The spot was checked on its region; CHECK re-validates it after the warmup.
            TeleportOptions options = free
                    ? TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.CHECK)
                    : new TeleportOptions(true, false, TeleportOptions.Safety.CHECK);
            teleport.teleport(target, destination, TeleportCause.RANDOM, options).whenComplete((result, error) -> {
                if (error == null && result == TeleportResult.SUCCESS) {
                    succeeded(sender, target, world, settings, free, economy, charge, destination);
                    return;
                }
                if (error != null) {
                    ctx.logger().error("Random teleport of {} to {} failed", target.getName(), destination, error);
                }
                refund(target, economy, charge, world);
            });
        });
    }

    private void succeeded(@Nullable CommandSender sender, Player target, World world, WorldSettings settings, boolean free,
            @Nullable EconomyService economy, BigDecimal charge, Location destination) {
        UUID uuid = target.getUniqueId();
        if (!free && settings.cooldownSeconds > 0 && !target.hasPermission(PERM_COOLDOWN_BYPASS)) {
            Instant end = cooldowns.start(uuid, world.getName(), Duration.ofSeconds(settings.cooldownSeconds));
            if (end != null) {
                store.saveCooldown(uuid, world.getName(), end.toEpochMilli()).exceptionally(error -> {
                    ctx.logger().error("Could not store the RTP cooldown of {} in {}", target.getName(), world.getName(), error);
                    return null;
                });
            }
        }
        messages.send(target, "rtp.teleported", Msg.location("to", destination));
        if (economy != null && charge.signum() > 0) {
            messages.send(target, "rtp.charged", Msg.unparsed("cost", economy.format(charge)));
        }
        if (sender != null && !sender.equals(target)) {
            messages.send(sender, "rtp.teleported-other", Msg.unparsed("player", target.getName()), Msg.location("to", destination));
        }
    }

    /** The teleport did not happen: give the money back (the teleport service already told the player why). */
    private void refund(Player target, @Nullable EconomyService economy, BigDecimal charge, World world) {
        if (economy == null || charge.signum() == 0) {
            return;
        }
        String amount = economy.format(charge);
        economy.deposit(target.getUniqueId(), charge, "rtp-refund:" + world.getName()).whenComplete((result, error) -> {
            if (error != null || result == null || !result.success()) {
                ctx.logger().error("REFUND FAILED: {} ({}) did not get {} back for a failed random teleport (status {})",
                        target.getName(), target.getUniqueId(), charge, result == null ? "error" : result.status(), error);
                messages.send(target, "rtp.refund-failed", Msg.unparsed("cost", amount));
                return;
            }
            messages.send(target, "rtp.refunded", Msg.unparsed("cost", amount));
        });
    }

    // ---- automatic teleports ----

    /** First join: runs one second after joining, on the player's thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        RtpConfig.FirstJoin cfg = config.get().firstJoin;
        Player player = event.getPlayer();
        if (!cfg.enabled || player.hasPlayedBefore()) {
            return;
        }
        ctx.scheduler().entityLater(player, () -> automatic(player, cfg.world, player.getWorld()), null, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        RtpConfig.Respawn cfg = config.get().respawn;
        if (!cfg.enabled || (cfg.onlyWithoutBed && (event.isBedSpawn() || event.isAnchorSpawn()))) {
            return;
        }
        Player player = event.getPlayer();
        automatic(player, cfg.world, event.getRespawnedLocation().getWorld());
    }

    private void automatic(Player player, String configuredWorld, World current) {
        if (!player.isOnline()) {
            return;
        }
        World world = configuredWorld == null || configuredWorld.isBlank() ? current : Bukkit.getWorld(configuredWorld.trim());
        if (world == null) {
            ctx.logger().warn("rtp.yml: world '{}' for automatic random teleports is not loaded", configuredWorld);
            return;
        }
        WorldSettings settings = config.get().settings(world.getName(), world.getEnvironment());
        if (settings == null) {
            ctx.logger().warn("rtp.yml: random teleport is disabled in {}, skipping the automatic teleport of {}",
                    world.getName(), player.getName());
            return;
        }
        start(null, player, world, settings, true, BigDecimal.ZERO);
    }

    // ---- RandomTeleportService ----

    @Override
    public CompletableFuture<Optional<Location>> findLocation(World world) {
        WorldSettings settings = config.get().settings(world.getName(), world.getEnvironment());
        return settings == null ? CompletableFuture.completedFuture(Optional.empty()) : searcher.find(world, settings);
    }

    @Override
    public CompletableFuture<TeleportResult> randomTeleport(Player player, World world) {
        return findLocation(world).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture(TeleportResult.UNSAFE)
                : teleport.teleport(player, found.get(), TeleportCause.RANDOM,
                        TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.CHECK)));
    }
}
