package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/** {@link ActionHost} backed by the server: one player, or a non-player command sender. */
final class BukkitActionHost implements ActionHost {
    private static final String COOLDOWN_PREFIX = "actions:";

    private final ModuleContext ctx;
    private final ActionsConfig config;
    private final @Nullable Player player;
    private final CommandSender audience;
    private final int depth;

    BukkitActionHost(ModuleContext ctx, ActionsConfig config, @Nullable Player player, CommandSender audience, int depth) {
        this.ctx = ctx;
        this.config = config;
        this.player = player;
        this.audience = audience;
        this.depth = depth;
    }

    /** Runs {@code task} on the thread that owns this sender. Completes exceptionally when the player is gone. */
    static CompletableFuture<Void> onOwner(Scheduler scheduler, @Nullable Player player, CommandSender sender, Runnable task) {
        if (player != null) {
            return scheduler.supplyOnEntity(player, () -> {
                task.run();
                return null;
            });
        }
        if (sender instanceof Entity entity) {
            return scheduler.supplyOnEntity(entity, () -> {
                task.run();
                return null;
            });
        }
        if (sender instanceof BlockCommandSender block) {
            Block b = block.getBlock();
            return scheduler.supplyAt(b.getLocation(), () -> {
                task.run();
                return null;
            });
        }
        return scheduler.supplyGlobal(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public boolean hasPlayer() {
        return player != null;
    }

    @Override
    public Map<String, String> builtIns() {
        if (player == null) {
            return Map.of();
        }
        Location at = player.getLocation();
        return Map.of("player", player.getName(), "uuid", player.getUniqueId().toString(),
                "world", player.getWorld().getName(), "x", Integer.toString(at.getBlockX()),
                "y", Integer.toString(at.getBlockY()), "z", Integer.toString(at.getBlockZ()));
    }

    @Override
    public boolean hasPermission(String node) {
        return player != null && player.hasPermission(node);
    }

    @Override
    public @Nullable String world() {
        return player == null ? null : player.getWorld().getName();
    }

    @Override
    public CompletableFuture<Optional<BigDecimal>> balance() {
        Optional<EconomyService> economy = ctx.services().get(EconomyService.class);
        if (player == null || economy.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        BigDecimal cached = economy.get().cachedBalance(player.getUniqueId());
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return economy.get().balance(player.getUniqueId()).handle((balance, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading the balance of {} for an action condition failed", player.getName(), error);
                return Optional.empty();
            }
            return Optional.ofNullable(balance);
        });
    }

    private Optional<AtriumUser> user() {
        return player == null ? Optional.empty() : ctx.users().cached(player.getUniqueId());
    }

    @Override
    public Optional<Duration> cooldownRemaining(String key) {
        return user().map(u -> ctx.cooldowns().remaining(u, COOLDOWN_PREFIX + key));
    }

    @Override
    public Optional<Duration> tryCooldown(String key, Duration duration) {
        return user().map(u -> ctx.cooldowns().tryAcquire(u, COOLDOWN_PREFIX + key, duration));
    }

    @Override
    public void clearCooldown(String key) {
        user().ifPresent(u -> ctx.cooldowns().clear(u, COOLDOWN_PREFIX + key));
    }

    @Override
    public double randomPercent() {
        return ThreadLocalRandom.current().nextDouble(100.0);
    }

    @Override
    public CompletableFuture<Boolean> console(String command) {
        if (config.logConsoleCommands) {
            ctx.logger().info("Action console command ({}): {}", player == null ? audience.getName() : player.getName(), command);
        }
        return ctx.scheduler().supplyGlobal(() -> SequenceRunner.withDepth(depth,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)));
    }

    @Override
    public boolean performCommand(String command) {
        return player != null && player.performCommand(command);
    }

    private Component render(String template, Map<String, String> vars) {
        return ctx.messages().parseTrusted(Variables.toMiniMessage(template, vars), Variables.resolver(vars));
    }

    @Override
    public void message(String template, Map<String, String> vars) {
        audience.sendMessage(render(template, vars));
    }

    @Override
    public void actionBar(String template, Map<String, String> vars) {
        if (player != null) {
            player.sendActionBar(render(template, vars));
        }
    }

    @Override
    public void title(String title, String subtitle, Map<String, String> vars) {
        if (player != null) {
            player.showTitle(Title.title(render(title, vars), subtitle.isEmpty() ? Component.empty() : render(subtitle, vars)));
        }
    }

    @Override
    public void sound(ActionParser.SoundSpec sound) {
        if (player != null) {
            player.playSound(Sound.sound(sound.key(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
        }
    }

    @Override
    public void notify(String key, TagResolver... resolvers) {
        ctx.messages().send(audience, "actions." + key, resolvers);
    }

    @Override
    public String formatMoney(BigDecimal amount) {
        return ctx.services().get(EconomyService.class).map(e -> e.format(amount)).orElse(amount.toPlainString());
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(BigDecimal amount) {
        return ctx.services().get(EconomyService.class)
                .map(e -> e.withdraw(playerId(), amount, "action"))
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("the economy module is not enabled")));
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(BigDecimal amount) {
        return ctx.services().get(EconomyService.class)
                .map(e -> e.deposit(playerId(), amount, "action"))
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("the economy module is not enabled")));
    }

    private java.util.UUID playerId() {
        if (player == null) {
            throw new IllegalStateException("no player");
        }
        return player.getUniqueId();
    }

    @Override
    public CompletableFuture<Boolean> teleport(StoredLocation target) {
        if (player == null) {
            return CompletableFuture.completedFuture(false);
        }
        Optional<Location> location = target.toLocation();
        if (location.isEmpty()) {
            ctx.logger().warn("Action teleport: world '{}' is not loaded", target.world());
            return CompletableFuture.completedFuture(false);
        }
        Optional<TeleportService> teleports = ctx.services().get(TeleportService.class);
        if (teleports.isPresent()) {
            return teleports.get().teleportNow(player, location.get(), TeleportCause.PLUGIN)
                    .thenApply(result -> result == TeleportResult.SUCCESS);
        }
        return player.teleportAsync(location.get(), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public Optional<StoredLocation> warp(String name) {
        return ctx.services().get(WarpService.class).flatMap(w -> w.location(name));
    }

    @Override
    public void resume(Runnable task, Runnable gone) {
        if (player != null) {
            ctx.track(ctx.scheduler().entity(player, task, gone));
        } else {
            onOwner(ctx.scheduler(), null, audience, task).exceptionally(error -> {
                gone.run();
                return null;
            });
        }
    }

    @Override
    public void later(long ticks, Runnable task, Runnable gone) {
        if (player != null) {
            ctx.track(ctx.scheduler().entityLater(player, task, gone, ticks));
        } else if (audience instanceof Entity entity) {
            ctx.track(ctx.scheduler().entityLater(entity, task, gone, ticks));
        } else if (audience instanceof BlockCommandSender block) {
            ctx.track(ctx.scheduler().regionLater(block.getBlock().getLocation(), task, ticks));
        } else {
            ctx.track(ctx.scheduler().globalLater(task, ticks));
        }
    }

    @Override
    public void warn(String message) {
        ctx.logger().warn(message);
    }

    @Override
    public void error(String message, Throwable error) {
        ctx.logger().error(message, error);
    }
}
