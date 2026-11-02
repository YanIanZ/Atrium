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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

public final class EconomyModule implements AtriumModule, Listener {
    private ModuleContext ctx;
    private EconomyStore store;
    private EconomyConfig config;
    private BigDecimal paymentMinimum, confirmationThreshold, chequeMaximum;
    private PayTax payTax;
    private Material chequeMaterial;
    private ChequeItem chequeItems;
    private final Map<UUID, PendingPayment> confirmations = new ConcurrentHashMap<>();
    private final Set<UUID> redeeming = ConcurrentHashMap.newKeySet();
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
        payTax = tax(config);
        chequeMaximum = store.money().amount(new BigDecimal(config.cheque.maximumAmount));
        chequeMaterial = Material.matchMaterial(config.cheque.material == null ? "" : config.cheque.material.trim());
        if (chequeMaterial == null || !chequeMaterial.isItem() || chequeMaterial.isAir()) {
            ctx.logger().warn("Unknown cheque material '{}' in economy.yml; using PAPER", config.cheque.material);
            chequeMaterial = Material.PAPER;
        }
        chequeItems = new ChequeItem(ctx.plugin(), chequeMaterial);
        if (paymentMinimum.signum() <= 0 || config.pageSize < 1 || config.pageSize > 100
                || config.leaderboardRefreshMinutes < 1 || config.vaultTimeoutMillis < 1 || config.vaultTimeoutMillis > 30000
                || chequeMaximum.signum() <= 0 || config.baltop.inactiveDays < 0) {
            throw new IllegalArgumentException("Invalid economy payment, cheque, leaderboard or Vault settings");
        }
        ctx.migrate(EconomyStore.MIGRATIONS);
        ctx.component(store.component);
        ctx.service(EconomyService.class, store);
        ctx.service(EconomyAccounts.class, store);
        ctx.listen(this);
        for (String command : List.of("balance", "pay", "baltop", "cheque"))
            ctx.permission("atrium." + command, PermissionDefault.TRUE, "Use /" + command);
        ctx.permission("atrium.pay.others", PermissionDefault.TRUE, "Pay other accounts");
        ctx.permission("atrium.pay.notax", PermissionDefault.FALSE, "Pay without the /pay tax");
        ctx.permission("atrium.balance.others", PermissionDefault.OP, "Read another player's balance");
        ctx.permission("atrium.eco.*", PermissionDefault.OP, "Economy administration");
        ctx.permission("atrium.eco.others", PermissionDefault.OP, "Manage other accounts");
        ctx.permission("atrium.cheque.redeem", PermissionDefault.TRUE, "Redeem a money cheque by right-clicking it");
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
        redeeming.clear();
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
                    .then(Commands.argument("amount", StringArgumentType.greedyString()).executes(this::pay))).build(), "Pay a player"));
        ctx.command(CommandSpec.of(Cmds.literal("cheque", "atrium.cheque")
                .executes(c -> { send(c.getSource().getSender(), "cheque-usage"); return Cmds.OK; })
                .then(Commands.argument("amount", StringArgumentType.greedyString())
                        .suggests(Cmds.suggest(() -> List.of("100", "1k", "10k", "rand:100-1000")))
                        .executes(this::writeCheque))
                .build(), "Create a tradeable money cheque: /cheque <amount>", "banknote", "moneycheque"));
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
            if (!action.equals("log")) target.then(Commands.argument("amount", StringArgumentType.greedyString()).executes(c -> admin(c, action)));
            eco.then(Cmds.literal(action).requires(source -> source.getSender().hasPermission("atrium.eco.*")
                    || source.getSender().hasPermission("atrium.eco." + action)).then(target));
        }
        ctx.command(CommandSpec.of(eco.build(), "Economy administration"));
        ctx.command(CommandSpec.of(Cmds.literal("baltop", "atrium.baltop").executes(c -> top(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> top(c, IntegerArgumentType.getInteger(c, "page"))))
                .build(), "Richest accounts"));
    }
    private AmountSyntax.Expression expression(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        try {
            return AmountSyntax.parse(StringArgumentType.getString(c, "amount"));
        } catch (IllegalArgumentException e) {
            throw ctx.commands().fail(c.getSource(), "economy.invalid-amount");
        }
    }

    /**
     * Resolves a parsed expression with the amount rounding of the store; null when the result is not a
     * usable amount (the caller already reported it to {@code sender}).
     */
    private BigDecimal resolved(CommandSender sender, AmountSyntax.Expression expression, BigDecimal base) {
        try {
            return store.money().amount(AmountSyntax.resolve(expression, base, store.money().scale,
                    ThreadLocalRandom.current()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            send(sender, "invalid-amount");
            return null;
        }
    }

    /** Runs {@code action} with the resolved amount after loading the optional base balance. */
    private void withAmount(CommandSender sender, UUID baseAccount, AmountSyntax.Expression expression,
            Consumer<BigDecimal> action) {
        if (!expression.needsBase()) {
            BigDecimal value = resolved(sender, expression, BigDecimal.ZERO);
            if (value != null) {
                action.accept(value);
            }
            return;
        }
        observe(store.balance(baseAccount).thenAccept(base -> onSender(sender, () -> {
            BigDecimal value = resolved(sender, expression, base);
            if (value != null) {
                action.accept(value);
            }
        })));
    }

    private int pay(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player sender = ctx.commands().player(c.getSource());
        if (!sender.hasPermission("atrium.pay.others")) throw ctx.commands().fail(c.getSource(), "core.no-permission-others",
                Msg.unparsed("permission", "atrium.pay.others"));
        UUID from = sender.getUniqueId();
        AmountSyntax.Expression expression = expression(c);
        String targetName = StringArgumentType.getString(c, "player");
        withAmount(sender, from, expression, value -> {
            if (value.compareTo(paymentMinimum) < 0) {
                send(sender, "minimum", Msg.unparsed("amount", store.format(paymentMinimum)));
                return;
            }
            resolve(sender, targetName, user -> payTo(sender, from, value, user));
        });
        return Cmds.OK;
    }

    private void payTo(Player sender, UUID from, BigDecimal value, AtriumUser user) {
        if (from.equals(user.uuid())) { send(sender, "self"); return; }
        if (value.compareTo(confirmationThreshold) > 0) {
            PendingPayment previous = confirmations.remove(from);
            long now = System.nanoTime();
            if (previous == null || !previous.target().equals(user.uuid()) || previous.amount().compareTo(value) != 0 || previous.expires() < now) {
                confirmations.put(from, new PendingPayment(user.uuid(), value, now + TimeUnit.SECONDS.toNanos(30)));
                send(sender, "confirm", Msg.unparsed("player", user.name()), Msg.unparsed("amount", store.format(value))); return;
            }
        }
        PayTax tax = sender.hasPermission("atrium.pay.notax") ? PayTax.NONE : payTax;
        BigDecimal taxAmount = tax.tax(value, store.money().scale, store.money().rounding);
        BigDecimal received = tax.receiverGets(value, taxAmount);
        BigDecimal paid = tax.senderPays(value, taxAmount);
        if (received.signum() <= 0) {
            send(sender, "tax-too-high", Msg.unparsed("tax", store.format(taxAmount)));
            return;
        }
        observe(store.transferTaxed(from, user.uuid(), received, taxAmount, "pay:" + from)
                .thenAccept(result -> {
                    result(sender, result);
                    if (result.success() && taxAmount.signum() > 0) {
                        send(sender, "tax-paid", Msg.unparsed("tax", store.format(taxAmount)),
                                Msg.unparsed("paid", store.format(paid)), Msg.unparsed("received", store.format(received)));
                    }
                }));
    }
    private int admin(CommandContext<CommandSourceStack> c, String action) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        if (!(sender instanceof Player player && player.getName().equalsIgnoreCase(name))
                && !sender.hasPermission("atrium.eco.others") && !sender.hasPermission("atrium.eco.*"))
            throw ctx.commands().fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", "atrium.eco.others"));
        if (!action.equals("log") && !action.equals("reset") && !Cmds.hasArgument(c, "amount")) {
            send(sender, "usage");
            return Cmds.OK;
        }
        AmountSyntax.Expression expression = action.equals("log") || action.equals("reset") ? null : expression(c);
        if (action.equals("reset") && Cmds.hasArgument(c, "amount")) expression(c); // validate the optional argument
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
            if (action.equals("reset")) {
                applyAdmin(sender, user, action, store.startingBalance(), reason);
                return;
            }
            withAmount(sender, user.uuid(), expression, value -> applyAdmin(sender, user, action, value, reason));
        });
        return Cmds.OK;
    }

    private void applyAdmin(CommandSender sender, AtriumUser user, String action, BigDecimal value, String reason) {
        CompletableFuture<TransactionResult> operation = switch (action) {
            case "give" -> store.deposit(user.uuid(), value, reason);
            case "take" -> store.withdraw(user.uuid(), value, reason);
            default -> store.set(user.uuid(), value, reason);
        };
        observe(operation.thenAccept(result -> result(sender, result)));
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
    private PayTax tax(EconomyConfig cfg) {
        EconomyConfig.Tax tax = cfg.payTax;
        if (tax == null || !tax.enabled) {
            return PayTax.NONE;
        }
        try {
            return new PayTax(number(tax.flat), number(tax.percent), number(tax.minimum),
                    number(tax.maximum), tax.deductOnTop);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid economy /pay tax settings: " + e.getMessage(), e);
        }
    }

    /** Tax settings may be written as "5" or "5%"; both mean five percent for the percentage field. */
    private static BigDecimal number(String value) {
        String text = value == null ? "" : value.trim();
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(text);
    }

    // ---- cheques ----

    private int writeCheque(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        if (!config.cheque.enabled) {
            throw ctx.commands().fail(c.getSource(), "economy.cheque-disabled");
        }
        AmountSyntax.Expression expression = expression(c);
        UUID owner = player.getUniqueId();
        withAmount(player, owner, expression, value -> {
            if (value.signum() <= 0 || value.compareTo(chequeMaximum) > 0) {
                send(player, "cheque-amount", Msg.unparsed("max", store.format(chequeMaximum)));
                return;
            }
            String id = UUID.randomUUID().toString();
            observe(store.createCheque(owner, id, value).thenAccept(result -> {
                if (!result.success()) {
                    send(player, "status-" + result.status().name().toLowerCase(Locale.ROOT));
                    return;
                }
                if (player.isOnline()) {
                    onSender(player, () -> giveCheque(player, id, value));
                    return;
                }
                // The player left while the note was being written: drop it where they logged out.
                ctx.logger().warn("{} left before cheque {} for {} could be handed over; dropping it at their last location",
                        player.getName(), id, value);
                Location at = player.getLocation().clone();
                ctx.scheduler().region(at, () -> {
                    if (at.getWorld() != null) {
                        at.getWorld().dropItem(at, note(player, id, value));
                    }
                });
            }));
        });
        return Cmds.OK;
    }

    private void giveCheque(Player player, String id, BigDecimal value) {
        ItemStack note = note(player, id, value);
        if (!player.getInventory().addItem(note).isEmpty()) {
            // Never destroy money: a full inventory drops the note at the player's feet instead.
            player.getWorld().dropItem(player.getLocation(), note);
        }
        send(player, "cheque-created", Msg.unparsed("amount", store.format(value)));
    }

    private ItemStack note(Player player, String id, BigDecimal value) {
        String formatted = store.format(value);
        return chequeItems.create(
                ctx.messages().render(player, "economy.cheque-name", Msg.unparsed("amount", formatted)),
                List.of(ctx.messages().render(player, "economy.cheque-lore", Msg.unparsed("amount", formatted))),
                id);
    }

    @SuppressWarnings("deprecation") // Material#isInteractable has no direct replacement for a block instance
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void redeem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        // Leave chests, doors, buttons and everything else interactable to vanilla.
        if (clicked != null && clicked.getType().isInteractable()) {
            return;
        }
        String id = chequeItems.idOf(event.getItem());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (!config.cheque.enabled) {
            send(player, "cheque-disabled");
            return;
        }
        if (!player.hasPermission("atrium.cheque.redeem")) {
            send(player, "cheque-no-permission");
            return;
        }
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && !config.cheque.allowCreativeRedemption) {
            send(player, "cheque-creative");
            return;
        }
        UUID owner = player.getUniqueId();
        if (!redeeming.add(owner)) {
            return; // a redemption is already running for this player
        }
        // The note is consumed only after the credit succeeded, so a storage failure never costs the player
        // the money. A duplicate copy of the same note loses the atomic claim and is worthless.
        observe(store.claimCheque(id, owner).thenCompose(claim -> {
            if (claim.status() == EconomyStore.Claim.MISSING) {
                send(player, "cheque-invalid");
                return CompletableFuture.completedFuture(null);
            }
            if (claim.status() == EconomyStore.Claim.ALREADY_REDEEMED) {
                send(player, "cheque-used");
                return CompletableFuture.completedFuture(null);
            }
            if (claim.status() == EconomyStore.Claim.FAILED) {
                send(player, "cheque-claim-failed");
                return CompletableFuture.completedFuture(null);
            }
            return store.deposit(owner, claim.value(), "cheque:redeem:" + id).thenApply(deposit -> {
                if (!deposit.success()) {
                    ctx.logger().warn("Crediting cheque {} to {} failed with {}; releasing the note", id, owner, deposit.status());
                    observe(store.unclaimCheque(id, owner).thenAccept(returned -> {
                        if (!returned) {
                            ctx.logger().error("CHEQUE UNCLAIM FAILED: {} ({}) could not release {}",
                                    player.getName(), owner, claim.value());
                        }
                    }));
                    send(player, "cheque-failed", Msg.unparsed("amount", store.format(claim.value())));
                    return null;
                }
                onSender(player, () -> consumeNote(player, id));
                send(player, "cheque-redeemed", Msg.unparsed("amount", store.format(claim.value())),
                        Msg.unparsed("balance", store.format(deposit.balance())));
                return deposit;
            });
        }).whenComplete((ignored, error) -> {
            redeeming.remove(owner);
            if (error != null) {
                ctx.logger().error("Redeeming cheque {} for {} failed", id, owner, error);
                send(player, "status-failed");
            }
        }));
    }

    /** Removes the redeemed note from the hand it was clicked with; a moved note is left alone. */
    private void consumeNote(Player player, String id) {
        if (id.equals(chequeItems.idOf(player.getInventory().getItemInMainHand()))) {
            player.getInventory().setItemInMainHand(ItemStack.empty());
        } else {
            ctx.logger().warn("Cheque {} was no longer in {}'s main hand after redemption; it was not consumed",
                    id, player.getName());
        }
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
        Set<String> excluded = new HashSet<>();
        if (config.baltop.excludedPlayers != null) {
            for (String name : config.baltop.excludedPlayers) {
                if (name != null && !name.isBlank()) {
                    excluded.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        long inactiveSince = config.baltop.inactiveDays <= 0 ? 0
                : System.currentTimeMillis() - Duration.ofDays(config.baltop.inactiveDays).toMillis();
        store.leaderboard(excluded, inactiveSince).whenComplete((snapshot, failure) -> {
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
