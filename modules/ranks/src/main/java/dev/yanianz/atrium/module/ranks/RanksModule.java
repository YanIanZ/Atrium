package dev.yanianz.atrium.module.ranks;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.ranks.RankService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.storage.Migration;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Config-driven rank ladder. Requirements are checked again immediately before charging, money is
 * withdrawn atomically after every other requirement passed and refunded when granting the group fails.
 * Rank rewards are recorded before commands run, so ranking down and up again never pays twice.
 */
public final class RanksModule implements AtriumModule, Listener, RankService {
    private static final String PERM_RANKUP = "atrium.rankup";
    private static final String PERM_RANKS = "atrium.ranks";
    private static final String PERM_RANKINFO = "atrium.rankinfo";
    private static final String PERM_SET = "atrium.rank.set";
    private static final String PERM_RANKDOWN = "atrium.rankdown";
    private static final String PERM_RANKDOWN_OTHERS = "atrium.rankdown.others";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<RanksConfig> config;
    private volatile RankLadder ladder;
    private RankStore store;
    private PlaytimeService playtime;
    private GroupBridge groups;
    private VoteStore voteStore;
    private VotifierHook votifier;
    private final Map<UUID, PendingRankup> confirmations = new ConcurrentHashMap<>();
    private final Map<String, PendingRankdown> downConfirmations = new ConcurrentHashMap<>();
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    private record PendingRankup(String targetRank, long expiresNanos) {}

    private record PendingRankdown(String leavingRank, long expiresNanos) {}

    private static final class RankFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String key;

        RankFailure(String key) {
            super(key);
            this.key = key;
        }
    }

    @Override
    public String id() {
        return "ranks";
    }

    @Override
    public String description() {
        return "Rank ladder with playtime, money and permission requirements";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("playtime");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.cmds = context.commands();
        this.config = ctx.config(RanksConfig.class, RanksConfig::new);
        validate(config.get());
        this.ladder = RankLadder.from(config.get());
        this.playtime = ctx.services().require(PlaytimeService.class);
        List<Migration> migrations = new ArrayList<>(RankStore.MIGRATIONS);
        migrations.addAll(VoteStore.MIGRATIONS);
        ctx.migrate(migrations);
        this.store = new RankStore(ctx.database(), () -> ladder.startingRank());
        this.voteStore = new VoteStore(ctx.database());
        ctx.component(store.component);
        ctx.service(RankService.class, this);
        ctx.listen(this);

        ctx.permission(PERM_RANKUP, PermissionDefault.TRUE, "Rank up and view the rank ladder");
        ctx.permission(PERM_RANKS, PermissionDefault.TRUE, "List ranks and requirement progress");
        ctx.permission(PERM_RANKINFO, PermissionDefault.TRUE, "View details of a rank");
        ctx.permission(PERM_SET, PermissionDefault.OP, "Set any player's rank");
        ctx.permission(PERM_RANKDOWN, PermissionDefault.TRUE, "Lower your own rank");
        ctx.permission(PERM_RANKDOWN_OTHERS, PermissionDefault.OP, "Lower another player's rank");
        ctx.permission(VoteCommands.PERM_VOTETOP, PermissionDefault.TRUE, "View the vote leaderboard");
        ctx.permission(VoteCommands.PERM_VOTEEDIT, PermissionDefault.OP,
                "Set, add, take or inspect stored vote counts");

        if (ctx.plugin().getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                groups = LuckPermsGroupBridge.create();
                ctx.logger().info("LuckPerms detected: rank groups will be changed through its API");
            } catch (Throwable t) {
                groups = null;
                ctx.logger().warn("LuckPerms is enabled but its API is unavailable; rank-up commands only", t);
            }
        }

        registerCommands();
        new VoteCommands(ctx, voteStore, config).register();
        if (config.get().votesEnabled) {
            this.votifier = new VotifierHook(ctx.plugin(), this::recordVote);
            if (votifier.register()) {
                ctx.logger().info("Votifier detected: votes are recorded for /votetop");
            } else {
                ctx.logger().info("Votifier is not installed; /votetop and /voteedit still use stored counts");
            }
        }
        ctx.track(ctx.scheduler().globalTimer(this::autoCycle, 20L * 20,
                20L * Math.max(10, config.get().autoRankupSeconds)));
    }

    private void recordVote(String playerName) {
        try {
            voteStore.increment(playerName).exceptionally(error -> {
                ctx.logger().warn("Recording a vote of {} failed", playerName, error);
                return null;
            });
        } catch (RuntimeException e) {
            ctx.logger().warn("Recording a vote of {} failed", playerName, e);
        }
    }

    @Override
    public void reload() throws Exception {
        validate(config.get());
        RankLadder next = RankLadder.from(config.get());
        this.ladder = next;
        confirmations.clear();
        downConfirmations.clear();
    }

    @Override
    public void disable() {
        if (votifier != null) {
            votifier.unregister();
            votifier = null;
        }
    }

    private void validate(RanksConfig cfg) {
        if (cfg.autoRankupSeconds < 10 || cfg.autoRankupSeconds > 3600
                || cfg.confirmSeconds < 5 || cfg.confirmSeconds > 300
                || cfg.rankdownConfirmSeconds < 5 || cfg.rankdownConfirmSeconds > 300
                || cfg.voteTopPageSize < 5 || cfg.voteTopPageSize > 50) {
            throw new IllegalArgumentException("Invalid ranks timer, confirmation timeout or vote page size");
        }
        RankLadder.from(cfg);
    }

    // ---- commands ----

    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("rankup", PERM_RANKUP)
                .executes(c -> rankup(c, false))
                .then(Cmds.literal("confirm").executes(c -> rankup(c, true)))
                .build(), "Rank up to the next rank"));
        ctx.command(CommandSpec.of(Cmds.literal("ranks", PERM_RANKS)
                .executes(this::listRanks)
                .build(), "List the rank ladder", "ranklist"));
        ctx.command(CommandSpec.of(Cmds.literal("rankinfo", PERM_RANKINFO)
                .executes(c -> rankInfo(c, null))
                .then(Commands.argument("rank", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> ladder.ids()))
                        .executes(c -> rankInfo(c, StringArgumentType.getString(c, "rank"))))
                .build(), "Show one rank's requirements"));
        ctx.command(CommandSpec.of(Cmds.literal("rankdown", PERM_RANKDOWN)
                .executes(c -> rankdown(c, null, false))
                .then(Cmds.literal("confirm").executes(c -> rankdown(c, null, true)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(Cmds.permission(PERM_RANKDOWN_OTHERS))
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> rankdown(c, StringArgumentType.getString(c, "player"), false))
                        .then(Cmds.literal("confirm").executes(c -> rankdown(c,
                                StringArgumentType.getString(c, "player"), true))))
                .build(), "Lower a player's rank by one step (no refund)"));
        ctx.command(CommandSpec.of(Cmds.literal("rank", PERM_SET)
                .executes(c -> { throw cmds.fail(c.getSource(), "ranks.usage"); })
                .then(Cmds.literal("set")
                        .executes(c -> { throw cmds.fail(c.getSource(), "ranks.usage"); })
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> ladder.ids()))
                                        .executes(this::setRank))))
                .build(), "Set a player's rank"));
        ctx.command(CommandSpec.of(Cmds.literal("rankset", PERM_SET)
                .executes(c -> {
                    ctx.messages().send(c.getSource().getSender(), "ranks.rankset-usage");
                    return Cmds.OK;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .then(Commands.argument("rank", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> ladder.ids()))
                                .executes(this::setRank)))
                .build(), "Set a player's rank without charging anything", "setrank"));
    }

    private int rankup(CommandContext<CommandSourceStack> c, boolean confirm) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        RankLadder currentLadder = ladder;
        RankLadder.Rank current = currentLadder.resolve(profile(player.getUniqueId()).rankId());
        RankLadder.Rank target = currentLadder.next(current).orElse(null);
        if (target == null) {
            send(player, "max");
            return Cmds.OK;
        }
        if (confirm || !config.get().confirmRankup) {
            if (confirm && config.get().confirmRankup) {
                PendingRankup pending = confirmations.remove(player.getUniqueId());
                if (pending == null || pending.expiresNanos() < System.nanoTime()) {
                    send(player, "confirm-expired");
                    return Cmds.OK;
                }
                if (!pending.targetRank().equalsIgnoreCase(target.id())) {
                    send(player, "changed");
                    return Cmds.OK;
                }
            }
            startRankup(player, current, target);
            return Cmds.OK;
        }
        progress(player, target).whenComplete((check, failure) -> onPlayer(player, () -> {
            if (failure != null) {
                failed(player, failure);
                return;
            }
            if (!check.met()) {
                sendRequirements(player, check);
                return;
            }
            long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.get().confirmSeconds);
            confirmations.put(player.getUniqueId(), new PendingRankup(target.id(), expires));
            Component prompt = ctx.messages().render(player, "ranks.confirm",
                            Msg.unparsed("rank", target.displayName()),
                            Msg.unparsed("money", formatMoney(target.money())),
                            Msg.number("seconds", config.get().confirmSeconds))
                    .clickEvent(ClickEvent.runCommand("/rankup confirm"))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(player, "ranks.confirm-hover")));
            player.sendMessage(prompt);
        }));
        return Cmds.OK;
    }

    private void startRankup(Player player, RankLadder.Rank current, RankLadder.Rank target) {
        if (!processing.add(player.getUniqueId())) {
            send(player, "busy");
            return;
        }
        executeRankup(player, current, target, true).whenComplete((value, failure) -> {
            processing.remove(player.getUniqueId());
            if (failure != null) {
                failed(player, failure);
            }
        });
    }

    // ---- /rankdown ----

    /**
     * Lowers a rank one step. No money or item is refunded: the player already used the previous rank and
     * its rewards. The {@code down-commands} of the rank being left run afterwards; a confirmation is used by
     * default so a mistyped command cannot drop someone by accident.
     */
    private int rankdown(CommandContext<CommandSourceStack> c, String targetName, boolean confirm)
            throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player self = cmds.player(c.getSource());
        Player target = self;
        if (targetName != null) {
            target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                throw cmds.fail(c.getSource(), "core.player-not-found");
            }
            if (!target.equals(self) && !sender.hasPermission(PERM_RANKDOWN_OTHERS)) {
                throw cmds.fail(c.getSource(), "core.no-permission-others",
                        Msg.unparsed("permission", PERM_RANKDOWN_OTHERS));
            }
        }
        RankLadder currentLadder = ladder;
        RankLadder.Rank current = currentLadder.resolve(profile(target.getUniqueId()).rankId());
        RankLadder.Rank previous = currentLadder.previous(current).orElse(null);
        if (previous == null) {
            send(sender, "down-lowest", Msg.unparsed("rank", current.displayName()));
            return Cmds.OK;
        }
        String key = sender.getName() + "|" + target.getUniqueId();
        if (config.get().confirmRankdown) {
            if (confirm) {
                PendingRankdown pending = downConfirmations.remove(key);
                if (pending == null || pending.expiresNanos() < System.nanoTime()
                        || !pending.leavingRank().equalsIgnoreCase(current.id())) {
                    send(sender, "down-expired");
                    return Cmds.OK;
                }
            } else {
                downConfirmations.put(key, new PendingRankdown(current.id(),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(config.get().rankdownConfirmSeconds)));
                String command = target.equals(self)
                        ? "/rankdown confirm" : "/rankdown " + target.getName() + " confirm";
                Component prompt = ctx.messages().render(sender, "ranks.down-confirm",
                                Msg.unparsed("player", target.getName()),
                                Msg.unparsed("rank", current.displayName()),
                                Msg.unparsed("next", previous.displayName()),
                                Msg.number("seconds", config.get().rankdownConfirmSeconds))
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "ranks.down-confirm-hover")));
                onSender(sender, () -> sender.sendMessage(prompt));
                return Cmds.OK;
            }
        }
        applyRankDown(sender, target, current, previous);
        return Cmds.OK;
    }

    private void applyRankDown(CommandSender sender, Player target, RankLadder.Rank current,
            RankLadder.Rank previous) {
        if (!processing.add(target.getUniqueId())) {
            send(sender, "busy");
            return;
        }
        AtriumUser user = ctx.users().user(target);
        setGroup(target.getUniqueId(), previous).thenCompose(ignored -> {
            user.update(store.component, profile -> profile.withRank(previous.id()));
            return ctx.users().saveNow(target.getUniqueId());
        }).thenCompose(ignored -> runRankCommands(target, current.downCommands()))
                .whenComplete((value, failure) -> {
                    processing.remove(target.getUniqueId());
                    if (failure != null) {
                        failed(sender, failure);
                        return;
                    }
                    onSender(sender, () -> send(sender, "down-success",
                            Msg.unparsed("player", target.getName()),
                            Msg.unparsed("rank", previous.displayName())));
                    if (!(sender instanceof Player viewer && viewer.equals(target))) {
                        onPlayer(target, () -> send(target, "down-target",
                                Msg.unparsed("rank", previous.displayName())));
                    }
                });
    }

    /** Manual and automatic paths both land here; requirements are checked again for TOCTOU safety. */
    private CompletableFuture<Void> executeRankup(Player player, RankLadder.Rank expectedCurrent,
            RankLadder.Rank target, boolean notify) {
        return progress(player, target).thenCompose(check -> {
            if (!check.met()) {
                if (notify) {
                    onPlayer(player, () -> sendRequirements(player, check));
                }
                return CompletableFuture.completedFuture(null);
            }
            RankProfile profile = profile(player.getUniqueId());
            RankLadder.Rank actual = ladder.resolve(profile.rankId());
            if (!actual.id().equalsIgnoreCase(expectedCurrent.id())
                    || ladder.next(actual).map(next -> !next.id().equalsIgnoreCase(target.id())).orElse(true)) {
                if (notify) {
                    onPlayer(player, () -> send(player, "changed"));
                }
                return CompletableFuture.completedFuture(null);
            }
            return chargeAndGrant(player, target);
        }).exceptionally(failure -> {
            Throwable cause = unwrap(failure);
            if (cause instanceof RankFailure rankFailure) {
                if (notify) {
                    onPlayer(player, () -> send(player, rankFailure.key));
                }
            } else if (notify) {
                failed(player, cause);
            } else {
                ctx.logger().warn("Automatic rank-up of {} failed", player.getName(), cause);
            }
            return null;
        });
    }

    private CompletableFuture<Void> chargeAndGrant(Player player, RankLadder.Rank target) {
        BigDecimal cost = target.money();
        AtomicBoolean charged = new AtomicBoolean();
        CompletableFuture<Void> charge = CompletableFuture.completedFuture(null);
        if (cost.signum() > 0) {
            EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
            if (economy == null) {
                throw new RankFailure("economy-unavailable");
            }
            charge = economy.withdraw(player.getUniqueId(), cost, "rankup:" + target.id())
                    .thenCompose(result -> {
                        if (!result.success()) {
                            return CompletableFuture.failedFuture(new RankFailure("insufficient-money"));
                        }
                        charged.set(true);
                        return CompletableFuture.completedFuture(null);
                    });
        }
        CompletableFuture<Void> applying = charge.thenCompose(v -> applyRank(player, target));
        return applying.whenComplete((value, failure) -> {
            if (failure != null && charged.get()) {
                refund(player, target, cost);
            }
        });
    }

    private CompletableFuture<Void> applyRank(Player player, RankLadder.Rank target) {
        AtriumUser user = ctx.users().user(player);
        CompletableFuture<Void> group = setGroup(player.getUniqueId(), target);
        return group.thenCompose(v -> {
            RankProfile before = user.get(store.component);
            boolean deliverCommands = !before.isRewarded(target.id());
            // Persist the reward marker before commands: a rank-down and rank-up cycle must not pay twice.
            user.update(store.component, profile -> {
                RankProfile next = profile.withRank(target.id());
                return deliverCommands ? next.markRewarded(target.id()) : next;
            });
            AtomicBoolean saveFailed = new AtomicBoolean();
            return ctx.users().saveNow(player.getUniqueId())
                    .handle((value, failure) -> {
                        if (failure != null) {
                            saveFailed.set(true);
                            ctx.logger().error("Saving the rank of {} failed; the periodic save will retry",
                                    player.getName(), failure);
                        }
                        return null;
                    })
                    .thenCompose(value -> deliverCommands
                            ? runRankCommands(player, target.commands())
                            : CompletableFuture.completedFuture(null))
                    .thenRun(() -> {
                        if (saveFailed.get()) {
                            send(player, "save-failed");
                        } else {
                            send(player, "rankup-success", Msg.unparsed("rank", target.displayName()));
                        }
                        if (config.get().broadcastEnabled()) {
                            Component announcement = ctx.messages().render(Locale.ROOT.toString(),
                                    "ranks.rankup-broadcast",
                                    Msg.unparsed("player", player.getName()),
                                    Msg.unparsed("rank", target.displayName()));
                            ctx.scheduler().global(() -> Bukkit.broadcast(announcement));
                        }
                    });
        });
    }

    /** Commands are best effort: a broken admin command must not undo a paid, granted rank. */
    private CompletableFuture<Void> runRankCommands(Player player, List<String> commands) {
        String name = player.getName();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid player name"));
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String template : commands) {
            String command = template.replace("{player}", name);
            chain = chain.thenCompose(v -> dispatch(command, player).handle((ok, failure) -> {
                if (failure != null) {
                    ctx.logger().warn("Rank command for {} failed: {}", player.getName(), command, failure);
                } else if (!ok) {
                    ctx.logger().warn("Rank command for {} returned false: {}", player.getName(), command);
                }
                return null;
            }));
        }
        return chain;
    }

    private CompletableFuture<Boolean> dispatch(String command, Player player) {
        return ctx.scheduler().supplyOnEntity(player, () -> {
            try {
                return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        });
    }

    private void refund(Player player, RankLadder.Rank target, BigDecimal cost) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            ctx.logger().error("Cannot refund {} to {}: economy service disappeared", formatMoney(cost), player.getName());
            onPlayer(player, () -> send(player, "refund-failed"));
            return;
        }
        economy.deposit(player.getUniqueId(), cost, "rankup-refund:" + target.id())
                .thenAccept(result -> {
                    if (!result.success()) {
                        ctx.logger().error("Refund of {} to {} failed with {}", formatMoney(cost),
                                player.getName(), result.status());
                        onPlayer(player, () -> send(player, "refund-failed"));
                    } else {
                        onPlayer(player, () -> send(player, "grant-failed-refunded",
                                Msg.unparsed("money", formatMoney(cost))));
                    }
                })
                .exceptionally(failure -> {
                    ctx.logger().error("Refund of {} to {} failed", formatMoney(cost), player.getName(), failure);
                    onPlayer(player, () -> send(player, "refund-failed"));
                    return null;
                });
    }

    private CompletableFuture<Void> setGroup(UUID player, RankLadder.Rank target) {
        if (groups == null) {
            return CompletableFuture.completedFuture(null);
        }
        return groups.setGroup(player, target.group(), ladder.managedGroups());
    }

    // ---- /ranks ----

    private int listRanks(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        RankLadder currentLadder = ladder;
        RankLadder.Rank current = currentLadder.resolve(profile(player.getUniqueId()).rankId());
        values(player).whenComplete((values, failure) -> onPlayer(player, () -> {
            if (failure != null) {
                failed(player, failure);
                return;
            }
            player.sendMessage(ctx.messages().render(player, "ranks.list-header"));
            for (RankLadder.Rank rank : currentLadder.ranks()) {
                RankProgress progress = new RankProgress(rank, values.playtime(), values.money(),
                        player.hasPermission(rank.permission()));
                Component status = ctx.messages().render(player, statusKey(currentLadder, current, rank));
                Component requirements = requirements(player, progress);
                player.sendMessage(ctx.messages().render(player, "ranks.entry",
                        Msg.unparsed("rank", rank.displayName()), Msg.component("status", status),
                        Msg.component("progress", requirements)));
            }
        }));
        return Cmds.OK;
    }

    private String statusKey(RankLadder currentLadder, RankLadder.Rank current, RankLadder.Rank rank) {
        int currentIndex = currentLadder.ranks().indexOf(current);
        int index = currentLadder.ranks().indexOf(rank);
        if (index == currentIndex) {
            return "ranks.status-current";
        }
        if (index < currentIndex) {
            return "ranks.status-reached";
        }
        return currentLadder.next(current).map(next -> next.id().equalsIgnoreCase(rank.id())).orElse(false)
                ? "ranks.status-next" : "ranks.status-locked";
    }

    // ---- /rankinfo ----

    private int rankInfo(CommandContext<CommandSourceStack> c, String requested) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        RankLadder currentLadder = ladder;
        RankLadder.Rank current = currentLadder.resolve(profile(player.getUniqueId()).rankId());
        RankLadder.Rank rank;
        if (requested != null) {
            rank = currentLadder.find(requested).orElseThrow(() -> cmds.fail(c.getSource(), "ranks.unknown-rank",
                    Msg.unparsed("rank", requested)));
        } else {
            rank = currentLadder.next(current).orElse(current);
        }
        values(player).whenComplete((values, failure) -> onPlayer(player, () -> {
            if (failure != null) {
                failed(player, failure);
                return;
            }
            RankProgress progress = new RankProgress(rank, values.playtime(), values.money(),
                    player.hasPermission(rank.permission()));
            player.sendMessage(ctx.messages().render(player, "ranks.info-header",
                    Msg.unparsed("rank", rank.displayName()), Msg.unparsed("id", rank.id())));
            player.sendMessage(ctx.messages().render(player, "ranks.info-status",
                    Msg.component("status", ctx.messages().render(player, statusKey(currentLadder, current, rank)))));
            if (!rank.group().isEmpty()) {
                player.sendMessage(ctx.messages().render(player, "ranks.info-group",
                        Msg.unparsed("group", rank.group())));
            }
            player.sendMessage(requirements(player, progress));
            player.sendMessage(ctx.messages().render(player, "ranks.info-commands",
                    Msg.number("count", rank.commands().size())));
        }));
        return Cmds.OK;
    }

    // ---- /rank set ----

    private int setRank(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String rankId = StringArgumentType.getString(c, "rank");
        RankLadder.Rank target = ladder.find(rankId)
                .orElseThrow(() -> cmds.fail(c.getSource(), "ranks.unknown-rank", Msg.unparsed("rank", rankId)));
        ctx.users().resolveAccount(name, false).thenCompose(found -> {
            if (found.isEmpty()) {
                sendCore(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            PlayerRef ref = found.get();
            return ctx.users().load(ref.uuid()).thenCompose(loaded -> {
                if (loaded.isEmpty()) {
                    sendCore(sender, "core.user-not-found", Msg.unparsed("name", name));
                    return CompletableFuture.completedFuture(null);
                }
                return setRankAdmin(sender, loaded.get(), target);
            });
        }).whenComplete((value, failure) -> {
            if (failure != null) {
                failed(sender, failure);
            }
        });
        return Cmds.OK;
    }

    private CompletableFuture<Void> setRankAdmin(CommandSender sender, AtriumUser user, RankLadder.Rank target) {
        return setGroup(user.uuid(), target).thenCompose(v -> {
            user.update(store.component, profile -> profile.withRank(target.id()));
            confirmations.remove(user.uuid());
            return ctx.users().saveNow(user.uuid()).handle((value, failure) -> {
                if (failure != null) {
                    throw new CompletionException(failure);
                }
                return null;
            });
        }).thenRun(() -> {
            onSender(sender, () -> sendCore(sender, "ranks.set-success",
                    Msg.unparsed("player", user.name()), Msg.unparsed("rank", target.displayName())));
            user.player().ifPresent(player -> onPlayer(player, () -> send(player, "set-target",
                    Msg.unparsed("rank", target.displayName()))));
        }).exceptionally(failure -> {
            ctx.logger().warn("Setting rank {} for {} failed", target.id(), user.name(), unwrap(failure));
            onSender(sender, () -> sendCore(sender, "ranks.set-failed",
                    Msg.unparsed("player", user.name())));
            return null;
        });
    }

    // ---- automatic rank-up ----

    private void autoCycle() {
        if (!config.get().autoRankup) {
            return;
        }
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            try {
                // One independent task per player: a throwable in one task (or its async chain) cannot
                // stop the cycle for the players after it.
                ctx.scheduler().entity(player, () -> autoCheck(player), null);
            } catch (Throwable t) {
                ctx.logger().warn("Scheduling automatic rank-up check for {} failed", player.getName(), t);
            }
        }
    }

    private void autoCheck(Player player) {
        try {
            if (!player.isOnline() || !processing.add(player.getUniqueId())) {
                return;
            }
            RankLadder currentLadder = ladder;
            RankLadder.Rank current = currentLadder.resolve(profile(player.getUniqueId()).rankId());
            RankLadder.Rank target = currentLadder.next(current).orElse(null);
            if (target == null || !target.autoRankup()) {
                processing.remove(player.getUniqueId());
                return;
            }
            executeRankup(player, current, target, false).whenComplete((value, failure) -> {
                processing.remove(player.getUniqueId());
                if (failure != null) {
                    ctx.logger().warn("Automatic rank-up of {} failed; continuing with other players",
                            player.getName(), failure);
                }
            });
        } catch (Throwable t) {
            processing.remove(player.getUniqueId());
            ctx.logger().warn("Automatic rank-up check for {} failed; continuing with other players",
                    player.getName(), t);
        }
    }

    // ---- requirements ----

    private record Values(long playtime, BigDecimal money) {}

    private CompletableFuture<Values> values(Player player) {
        CompletableFuture<Long> total = playtime.totalPlaytime(player.getUniqueId());
        CompletableFuture<BigDecimal> balance = balance(player.getUniqueId());
        return total.thenCombine(balance, Values::new);
    }

    private CompletableFuture<BigDecimal> balance(UUID uuid) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            return CompletableFuture.completedFuture(BigDecimal.ZERO);
        }
        return economy.balance(uuid).exceptionally(failure -> {
            ctx.logger().warn("Reading the balance of {} for ranks failed", uuid, failure);
            return BigDecimal.ZERO;
        });
    }

    private CompletableFuture<RankProgress> progress(Player player, RankLadder.Rank rank) {
        // The permission check must happen on the player's thread; playtime and economy complete later.
        boolean permission = player.hasPermission(rank.permission());
        return values(player).thenApply(values -> new RankProgress(rank, values.playtime(), values.money(),
                permission));
    }

    private Component requirements(Player player, RankProgress progress) {
        List<Component> parts = new ArrayList<>();
        RankLadder.Rank rank = progress.rank();
        if (rank.playtimeSeconds() > 0) {
            parts.add(ctx.messages().render(player, progress.playtimeMet()
                            ? "ranks.req-playtime-met" : "ranks.req-playtime-unmet",
                    Msg.duration("have", Duration.ofSeconds(progress.playtimeSeconds())),
                    Msg.duration("need", Duration.ofSeconds(rank.playtimeSeconds()))));
        }
        if (rank.money().signum() > 0) {
            parts.add(ctx.messages().render(player, progress.moneyMet()
                            ? "ranks.req-money-met" : "ranks.req-money-unmet",
                    Msg.unparsed("have", formatMoney(progress.money())),
                    Msg.unparsed("need", formatMoney(rank.money()))));
        }
        if (!rank.permission().isEmpty()) {
            parts.add(ctx.messages().render(player, progress.permissionMet()
                            ? "ranks.req-permission-met" : "ranks.req-permission-unmet",
                    Msg.unparsed("permission", rank.permission())));
        }
        if (parts.isEmpty()) {
            return ctx.messages().render(player, "ranks.req-none");
        }
        Component separator = ctx.messages().render(player, "ranks.separator");
        return Component.join(JoinConfiguration.separator(separator), parts);
    }

    private void sendRequirements(Player player, RankProgress progress) {
        player.sendMessage(ctx.messages().render(player, "ranks.requirements-header",
                Msg.unparsed("rank", progress.rank().displayName())));
        RankLadder.Rank rank = progress.rank();
        if (rank.playtimeSeconds() > 0) {
            player.sendMessage(ctx.messages().render(player, "ranks.need-playtime",
                    Msg.duration("have", Duration.ofSeconds(progress.playtimeSeconds())),
                    Msg.duration("need", Duration.ofSeconds(rank.playtimeSeconds()))));
        }
        if (rank.money().signum() > 0) {
            player.sendMessage(ctx.messages().render(player, "ranks.need-money",
                    Msg.unparsed("have", formatMoney(progress.money())),
                    Msg.unparsed("need", formatMoney(rank.money()))));
        }
        if (!rank.permission().isEmpty()) {
            player.sendMessage(ctx.messages().render(player, "ranks.need-permission",
                    Msg.unparsed("permission", rank.permission())));
        }
    }

    // ---- RankService ----

    @Override
    public List<String> ranks() {
        return ladder.ids();
    }

    @Override
    public String startingRank() {
        return ladder.startingRank();
    }

    @Override
    public CompletableFuture<Optional<String>> currentRank(UUID player) {
        Optional<AtriumUser> cached = ctx.users().cached(player);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(ladder.resolve(profile(cached.get()).rankId()).id()));
        }
        return store.storedRank(player).thenApply(id -> Optional.of(ladder.resolve(id).id()));
    }

    // ---- helpers ----

    private RankProfile profile(UUID uuid) {
        Optional<AtriumUser> cached = ctx.users().cached(uuid);
        if (cached.isEmpty()) {
            return RankProfile.initial(ladder.startingRank());
        }
        return profile(cached.get());
    }

    private RankProfile profile(AtriumUser user) {
        try {
            return user.get(store.component);
        } catch (IllegalStateException e) {
            return RankProfile.initial(ladder.startingRank());
        }
    }

    private String formatMoney(BigDecimal amount) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        return economy == null ? amount.stripTrailingZeros().toPlainString() : economy.format(amount);
    }

    private void onPlayer(Player player, Runnable work) {
        try {
            ctx.scheduler().entity(player, work, null);
        } catch (Throwable t) {
            ctx.logger().warn("Cannot run rank message task for {}", player.getName(), t);
        }
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            onPlayer(player, work);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void send(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, "ranks." + key, tags));
    }

    /** Sends an already-prefixed ranks key without adding the prefix twice. */
    private void sendCore(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, key, tags));
    }

    private void failed(CommandSender sender, Throwable failure) {
        ctx.logger().warn("Ranks command failed", unwrap(failure));
        sendCore(sender, "core.error");
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        confirmations.remove(event.getPlayer().getUniqueId());
        String suffix = "|" + event.getPlayer().getUniqueId();
        downConfirmations.keySet().removeIf(key -> key.endsWith(suffix));
    }
}
