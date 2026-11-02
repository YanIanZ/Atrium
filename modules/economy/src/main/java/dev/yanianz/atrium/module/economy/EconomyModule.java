package dev.yanianz.atrium.module.economy;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.economy.EconomyAccounts;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

public final class EconomyModule implements AtriumModule, Listener {
    private ModuleContext ctx;
    private EconomyStore store;
    private EconomyConfig config;
    private BigDecimal paymentMinimum, confirmationThreshold;
    private final Map<UUID, PendingPayment> confirmations = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile List<EconomyStore.Entry> leaders = List.of();
    private AutoCloseable vault;
    private record PendingPayment(UUID target, BigDecimal amount, long expires) {}

    @Override public String id() { return "economy"; }
    @Override public String description() { return "Transactional economy, payments and balances"; }
    @Override public void enable(ModuleContext context) throws Exception {
        ctx = context;
        config = ctx.config(EconomyConfig.class, EconomyConfig::new).get();
        store = new EconomyStore(ctx.database(), config, ctx.logger());
        paymentMinimum = store.money().parse(config.minimumPayment);
        confirmationThreshold = store.money().parse(config.confirmationThreshold);
        if (paymentMinimum.signum() <= 0 || config.pageSize < 1 || config.pageSize > 100
                || config.leaderboardRefreshMinutes < 1 || config.vaultTimeoutMillis < 1 || config.vaultTimeoutMillis > 30000) {
            throw new IllegalArgumentException("Invalid economy payment, leaderboard or Vault settings");
        }
        ctx.migrate(EconomyStore.MIGRATIONS);
        ctx.component(store.component);
        ctx.service(EconomyService.class, store);
        ctx.service(EconomyAccounts.class, store);
        ctx.listen(this);
        for (String command : List.of("balance", "pay", "baltop"))
            ctx.permission("atrium." + command, PermissionDefault.TRUE, "Use /" + command);
        ctx.permission("atrium.pay.others", PermissionDefault.TRUE, "Pay other accounts");
        ctx.permission("atrium.balance.others", PermissionDefault.OP, "Read another player's balance");
        ctx.permission("atrium.eco.*", PermissionDefault.OP, "Economy administration");
        ctx.permission("atrium.eco.others", PermissionDefault.OP, "Manage other accounts");
        for (String action : List.of("give", "take", "set", "reset", "log"))
            ctx.permission("atrium.eco." + action, PermissionDefault.OP, "Economy " + action);
        registerCommands();
        refresh();
        Duration interval = Duration.ofMinutes(config.leaderboardRefreshMinutes);
        ctx.track(ctx.scheduler().asyncTimer(this::refresh, interval, interval));
        for (AtriumUser user : ctx.users().online()) observe(store.online(user.uuid()));
        if (ctx.plugin().getServer().getPluginManager().isPluginEnabled("Vault")) {
            vault = VaultBridge.register(ctx, store, config);
        }
    }
    @Override public void disable() {
        if (vault != null) {
            try { vault.close(); } catch (Exception e) { ctx.logger().warn("Cannot unregister Vault economy", e); }
        }
        confirmations.clear();
        if (store != null) observe(store.close());
    }
    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("balance", "atrium.balance")
                .executes(c -> {
                    Player player = ctx.commands().player(c.getSource());
                    showBalance(c.getSource().getSender(), player.getUniqueId(), player.getName()); return Cmds.OK;
                }).then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            String name = StringArgumentType.getString(c, "player");
                            if (!(sender instanceof Player player && player.getName().equalsIgnoreCase(name))
                                    && !sender.hasPermission("atrium.balance.others")) throw ctx.commands().fail(c.getSource(), "core.no-permission-others",
                                            Msg.unparsed("permission", "atrium.balance.others"));
                            resolve(sender, name, user -> showBalance(sender, user.uuid(), user.name())); return Cmds.OK;
                        })).build(), "View balance", "bal"));
        ctx.command(CommandSpec.of(Cmds.literal("pay", "atrium.pay")
                .executes(c -> { send(c.getSource().getSender(), "pay-usage"); return Cmds.OK; })
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(Cmds.knownPlayers(ctx.users()))
                    .then(Commands.argument("amount", StringArgumentType.word()).executes(this::pay))).build(), "Pay a player"));
        var eco = Cmds.literal("eco").requires(source -> source.getSender().hasPermission("atrium.eco.*")
                        || List.of("give", "take", "set", "reset", "log").stream()
                            .anyMatch(action -> source.getSender().hasPermission("atrium.eco." + action)))
                .executes(c -> { send(c.getSource().getSender(), "usage"); return Cmds.OK; });
        for (String action : List.of("give", "take", "set", "reset", "log")) {
            var target = Commands.argument("player", StringArgumentType.word())
                    .suggests(Cmds.knownPlayers(ctx.users()));
            if (action.equals("log") || action.equals("reset")) {
                target.executes(c -> admin(c, action));
            } else {
                target.executes(c -> { send(c.getSource().getSender(), "usage"); return Cmds.OK; });
            }
            if (!action.equals("log")) target.then(Commands.argument("amount", StringArgumentType.word()).executes(c -> admin(c, action)));
            eco.then(Cmds.literal(action).requires(source -> source.getSender().hasPermission("atrium.eco.*")
                    || source.getSender().hasPermission("atrium.eco." + action)).then(target));
        }
        ctx.command(CommandSpec.of(eco.build(), "Economy administration"));
        ctx.command(CommandSpec.of(Cmds.literal("baltop", "atrium.baltop").executes(c -> top(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> top(c, IntegerArgumentType.getInteger(c, "page"))))
                .build(), "Richest accounts"));
    }
    private BigDecimal amount(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        try { return store.money().parse(StringArgumentType.getString(c, "amount")); }
        catch (IllegalArgumentException | ArithmeticException e) { throw ctx.commands().fail(c.getSource(), "economy.invalid-amount"); }
    }
    private int pay(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player sender = ctx.commands().player(c.getSource());
        if (!sender.hasPermission("atrium.pay.others")) throw ctx.commands().fail(c.getSource(), "core.no-permission-others",
                Msg.unparsed("permission", "atrium.pay.others"));
        UUID from = sender.getUniqueId();
        BigDecimal value = amount(c);
        if (value.compareTo(paymentMinimum) < 0) throw ctx.commands().fail(c.getSource(), "economy.minimum",
                Msg.unparsed("amount", store.format(paymentMinimum)));
        resolve(sender, StringArgumentType.getString(c, "player"), user -> {
            if (from.equals(user.uuid())) { send(sender, "self"); return; }
            if (value.compareTo(confirmationThreshold) > 0) {
                PendingPayment previous = confirmations.remove(from);
                long now = System.nanoTime();
                if (previous == null || !previous.target().equals(user.uuid()) || previous.amount().compareTo(value) != 0 || previous.expires() < now) {
                    confirmations.put(from, new PendingPayment(user.uuid(), value, now + TimeUnit.SECONDS.toNanos(30)));
                    send(sender, "confirm", Msg.unparsed("player", user.name()), Msg.unparsed("amount", store.format(value))); return;
                }
            }
            observe(store.transfer(from, user.uuid(), value, "pay:" + from).thenAccept(result -> result(sender, result)));
        });
        return Cmds.OK;
    }
    private int admin(CommandContext<CommandSourceStack> c, String action) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        if (!(sender instanceof Player player && player.getName().equalsIgnoreCase(name))
                && !sender.hasPermission("atrium.eco.others") && !sender.hasPermission("atrium.eco.*"))
            throw ctx.commands().fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", "atrium.eco.others"));
        BigDecimal value = action.equals("log") ? BigDecimal.ZERO : action.equals("reset") ? store.startingBalance() : amount(c);
        // Validate the optional reset argument too; reset always restores configured starting balance.
        if (action.equals("reset") && Cmds.hasArgument(c, "amount")) amount(c);
        String reason = "eco:" + action + ":" + sender.getName();
        resolve(sender, name, user -> {
            if (action.equals("log")) {
                observe(store.history(user.uuid()).thenAccept(entries -> {
                    if (entries.isEmpty()) send(sender, "empty");
                    for (var entry : entries) send(sender, "log", Msg.unparsed("time", Instant.ofEpochMilli(entry.created()).toString()),
                            Msg.unparsed("amount", entry.amount()), Msg.unparsed("balance", entry.balance()), Msg.unparsed("reason", entry.reason()));
                }).exceptionally(failure -> { failed(sender, failure); return null; }));
                return;
            }
            CompletableFuture<TransactionResult> operation = switch (action) {
                case "give" -> store.deposit(user.uuid(), value, reason);
                case "take" -> store.withdraw(user.uuid(), value, reason);
                default -> store.set(user.uuid(), value, reason);
            };
            observe(operation.thenAccept(result -> result(sender, result)));
        });
        return Cmds.OK;
    }
    private int top(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        List<EconomyStore.Entry> snapshot = leaders;
        long start = (long) (page - 1) * config.pageSize;
        if (start >= snapshot.size()) { send(sender, "empty"); return Cmds.OK; }
        send(sender, "top-header", Msg.unparsed("page", Integer.toString(page)));
        for (int i = (int) start; i < Math.min(start + config.pageSize, snapshot.size()); i++) {
            var entry = snapshot.get(i);
            send(sender, "top-entry", Msg.unparsed("rank", Integer.toString(i + 1)), Msg.unparsed("player", entry.name()),
                    Msg.unparsed("amount", store.format(entry.balance())));
        }
        return Cmds.OK;
    }
    private void resolve(CommandSender sender, String name, Consumer<AtriumUser> found) {
        observe(ctx.users().loadByName(name).thenAccept(user -> {
            // Completion can run on an I/O thread; continue player commands on their owning thread.
            onSender(sender, () -> {
                if (user.isEmpty()) sendRaw(sender, "core.user-not-found", Msg.unparsed("name", name));
                else found.accept(user.get());
            });
        }).exceptionally(failure -> { failed(sender, failure); return null; }));
    }
    private void showBalance(CommandSender sender, UUID id, String name) {
        observe(store.balance(id).thenAccept(value -> send(sender, "balance", Msg.unparsed("player", name), Msg.unparsed("amount", store.format(value))))
                .exceptionally(failure -> { failed(sender, failure); return null; }));
    }
    private void result(CommandSender sender, TransactionResult result) {
        send(sender, result.success() ? "success" : "status-" + result.status().name().toLowerCase(Locale.ROOT),
                Msg.unparsed("amount", store.format(result.amount())), Msg.unparsed("balance", store.format(result.balance())));
    }
    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) ctx.scheduler().entity(player, work, null);
        else ctx.scheduler().global(work);
    }
    private void send(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, "economy." + key, tags));
    }
    private void sendRaw(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, key, tags));
    }
    private void failed(CommandSender sender, Throwable failure) {
        ctx.logger().warn("Economy command failed", failure); send(sender, "status-failed");
    }
    private void observe(CompletableFuture<?> future) {
        future.exceptionally(failure -> { ctx.logger().warn("Economy background operation failed", failure); return null; });
    }
    private void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        store.leaderboard().whenComplete((snapshot, failure) -> {
            if (failure != null) ctx.logger().warn("Cannot refresh economy leaderboard; retaining previous snapshot", failure);
            else leaders = snapshot;
            refreshing.set(false);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR) public void preLogin(AsyncPlayerPreLoginEvent event) {
        UUID id = event.getUniqueId();
        // Warming here is intentional: this thread already blocks for the core user data, and the player
        // is not in game yet, so every Vault read for this account is answered from a cached value later.
        // The value waits in the bounded read cache, so a denied login cannot leak an online entry.
        try { store.warm(id).get(config.vaultTimeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.logger().warn("Interrupted while warming the economy balance for {}", id, e);
        } catch (ExecutionException | TimeoutException e) {
            ctx.logger().warn("Cannot warm the economy balance for {} before login; the first read may be deferred", id, e);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        // Fallback for a warm that failed or timed out before login; it also publishes the committed value.
        observe(store.online(event.getPlayer().getUniqueId()));
    }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        confirmations.remove(id);
        // Eviction waits for queued writes, so a commit cannot repopulate the cache after the player left.
        observe(store.offline(id));
    }
}
