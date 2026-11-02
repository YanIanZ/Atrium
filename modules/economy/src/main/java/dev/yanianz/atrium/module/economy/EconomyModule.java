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
import dev.yanianz.atrium.module.economy.EconomyStore.Key;
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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
    /** Leaderboard snapshot per group storage key. */
    private volatile Map<String, List<EconomyStore.Entry>> leaders = Map.of();
    private AutoCloseable vault;
    private record PendingPayment(Key from, Key target, BigDecimal amount, long expires) {}
    /** Trailing command words with the world-group selector removed; {@code key} is null when none was given. */
    private record Selected(String rest, String key) {}

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
        // MySQL: complete a half-applied migration 3 first, so the normal run does not fail on a duplicate column.
        EconomyStore.repairPartialGroupMigration(ctx.database()).get(30, java.util.concurrent.TimeUnit.SECONDS);
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
        ctx.permission("atrium.eco.group", PermissionDefault.OP,
                "Choose a world group with g:<name> or --group <name> in /balance, /pay, /eco and /baltop");
        ctx.permission("atrium.cheque.redeem", PermissionDefault.TRUE, "Redeem a money cheque by right-clicking it");
        for (String action : List.of("give", "take", "set", "reset", "log", "groups"))
            ctx.permission("atrium.eco." + action, PermissionDefault.OP, "Economy " + action);
        registerCommands();
        refresh();
        Duration interval = Duration.ofMinutes(config.leaderboardRefreshMinutes);
        ctx.track(ctx.scheduler().asyncTimer(this::refresh, interval, interval));
        for (AtriumUser user : ctx.users().online()) {
            Player player = Bukkit.getPlayer(user.uuid());
            observe(store.online(user.uuid(), player == null ? null : player.getWorld().getName()));
        }
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
                .executes(c -> balance(c, null, ""))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> balance(c, StringArgumentType.getString(c, "player"), ""))
                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                .suggests(groupSuggestions())
                                .executes(c -> balance(c, StringArgumentType.getString(c, "player"),
                                        StringArgumentType.getString(c, "options")))))
                .build(), "View balance (g:<group> picks a world group)", "bal"));
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
                        || List.of("give", "take", "set", "reset", "log", "groups").stream()
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
            // The greedy tail also carries the optional g:<group> / --group <group> selector.
            target.then(Commands.argument(action.equals("log") ? "options" : "amount", StringArgumentType.greedyString())
                    .suggests(groupSuggestions()).executes(c -> admin(c, action)));
            eco.then(Cmds.literal(action).requires(source -> source.getSender().hasPermission("atrium.eco.*")
                    || source.getSender().hasPermission("atrium.eco." + action)).then(target));
        }
        eco.then(Cmds.literal("groups").requires(source -> source.getSender().hasPermission("atrium.eco.*")
                || source.getSender().hasPermission("atrium.eco.groups")).executes(this::groups));
        ctx.command(CommandSpec.of(eco.build(), "Economy administration"));
        ctx.command(CommandSpec.of(Cmds.literal("baltop", "atrium.baltop").executes(c -> top(c, 1, ""))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(c -> top(c, IntegerArgumentType.getInteger(c, "page"), ""))
                        .then(Commands.argument("options", StringArgumentType.greedyString()).suggests(groupSuggestions())
                                .executes(c -> top(c, IntegerArgumentType.getInteger(c, "page"),
                                        StringArgumentType.getString(c, "options")))))
                .then(Commands.argument("options", StringArgumentType.greedyString()).suggests(groupSuggestions())
                        .executes(c -> top(c, 0, StringArgumentType.getString(c, "options"))))
                .build(), "Richest accounts (g:<group> picks a world group)"));
    }
    private AmountSyntax.Expression expression(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return expression(c, StringArgumentType.getString(c, "amount"));
    }

    private AmountSyntax.Expression expression(CommandContext<CommandSourceStack> c, String text) throws CommandSyntaxException {
        try {
            return AmountSyntax.parse(text);
        } catch (IllegalArgumentException e) {
            throw ctx.commands().fail(c.getSource(), "economy.invalid-amount");
        }
    }

    // ---- world groups ----

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> groupSuggestions() {
        return Cmds.suggest(() -> store.worldGroups().names().stream().map(name -> "g:" + name).toList());
    }

    /**
     * Splits the optional world-group selector off trailing text. Choosing a group needs
     * {@code atrium.eco.group}; an unknown group fails with the list of configured groups.
     */
    private Selected select(CommandContext<CommandSourceStack> c, String text) throws CommandSyntaxException {
        GroupOption option;
        try {
            option = GroupOption.parse(text);
        } catch (IllegalArgumentException e) {
            throw ctx.commands().fail(c.getSource(), "economy.group-syntax");
        }
        if (option.group() == null) return new Selected(option.rest(), null);
        CommandSender sender = c.getSource().getSender();
        if (!sender.hasPermission("atrium.eco.group") && !sender.hasPermission("atrium.eco.*")) {
            throw ctx.commands().fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", "atrium.eco.group"));
        }
        String key = store.key(option.group());
        if (key == null) {
            throw ctx.commands().fail(c.getSource(), "economy.group-unknown", Msg.unparsed("group", option.group()),
                    Msg.unparsed("groups", String.join(", ", store.worldGroups().names())));
        }
        return new Selected(option.rest(), key);
    }

    /** Group of the world the sender stands in; the default group for the console. Call on the sender's thread. */
    private String senderGroup(CommandSender sender) {
        return sender instanceof Player player ? store.worldGroups().keyOfWorld(player.getWorld().getName())
                : WorldGroups.DEFAULT_KEY;
    }

    /** A target's group: its current world's group while online, otherwise the sender's group. */
    private String targetGroup(String senderGroup, UUID target) {
        return store.isOnline(target) ? store.contextGroup(target) : senderGroup;
    }

    private String groupName(String key) { return store.worldGroups().display(key); }

    private int balance(CommandContext<CommandSourceStack> c, String name, String options) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        // "/balance g:x" and "/balance --group x" select a group for the sender's own balance.
        if (name != null && (name.toLowerCase(Locale.ROOT).startsWith("g:") || name.toLowerCase(Locale.ROOT).startsWith("--group"))) {
            options = (name + " " + options).trim();
            name = null;
        }
        Selected selected = select(c, options);
        if (!selected.rest().isEmpty()) throw ctx.commands().fail(c.getSource(), "economy.group-syntax");
        String fromSender = senderGroup(sender);
        if (name == null) {
            Player player = ctx.commands().player(c.getSource());
            String key = selected.key() != null ? selected.key() : fromSender;
            showBalance(sender, new Key(player.getUniqueId(), key), player.getName());
            return Cmds.OK;
        }
        if (!(sender instanceof Player player && player.getName().equalsIgnoreCase(name))
                && !sender.hasPermission("atrium.balance.others")) throw ctx.commands().fail(c.getSource(), "core.no-permission-others",
                        Msg.unparsed("permission", "atrium.balance.others"));
        String target = name;
        resolve(sender, target, user -> showBalance(sender, new Key(user.uuid(),
                selected.key() != null ? selected.key() : targetGroup(fromSender, user.uuid())), user.name()));
        return Cmds.OK;
    }

    private int groups(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        WorldGroups groups = store.worldGroups();
        List<String> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) worlds.add(world.getName());
        observe(store.storedGroups().thenAccept(stored -> {
            send(sender, groups.enabled() ? "groups-enabled" : "groups-disabled",
                    Msg.unparsed("default", groups.defaultName()),
                    Msg.unparsed("cross", Boolean.toString(groups.allowCrossGroupPay())));
            for (String key : groups.keys()) {
                List<String> patterns = groups.worlds(key);
                send(sender, "groups-entry", Msg.unparsed("group", groups.display(key)),
                        Msg.unparsed("worlds", patterns.isEmpty() ? "-" : String.join(", ", patterns)),
                        Msg.unparsed("accounts", Long.toString(stored.getOrDefault(key, 0L))),
                        Msg.unparsed("role", key.equals(WorldGroups.DEFAULT_KEY) ? "default" : "group"));
            }
            for (String world : worlds) {
                send(sender, "groups-world", Msg.unparsed("world", world),
                        Msg.unparsed("group", groups.display(groups.keyOfWorld(world))));
            }
            for (Map.Entry<String, Long> entry : stored.entrySet()) {
                if (!groups.keys().contains(entry.getKey())) {
                    send(sender, "groups-stored", Msg.unparsed("group", entry.getKey()),
                            Msg.unparsed("accounts", Long.toString(entry.getValue())));
                }
            }
        }).exceptionally(failure -> { failed(sender, failure); return null; }));
        return Cmds.OK;
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
    private void withAmount(CommandSender sender, Key baseAccount, AmountSyntax.Expression expression,
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
        Selected selected = select(c, StringArgumentType.getString(c, "amount"));
        AmountSyntax.Expression expression = expression(c, selected.rest());
        Key from = new Key(sender.getUniqueId(), selected.key() != null ? selected.key() : senderGroup(sender));
        String targetName = StringArgumentType.getString(c, "player");
        withAmount(sender, from, expression, value -> {
            if (value.compareTo(paymentMinimum) < 0) {
                send(sender, "minimum", Msg.unparsed("amount", store.format(paymentMinimum)));
                return;
            }
            resolve(sender, targetName, user -> {
                // An explicit group applies to both sides. Otherwise an online receiver is paid in the group of
                // the world it is in, an offline receiver in the payer's group.
                String to = WorldGroups.receiverGroup(from.group(), selected.key(),
                        store.isOnline(user.uuid()) ? store.contextGroup(user.uuid()) : null);
                if (!store.worldGroups().permits(from.group(), to)) {
                    send(sender, "pay-cross-group", Msg.unparsed("player", user.name()),
                            Msg.unparsed("target_group", groupName(to)), Msg.unparsed("group", groupName(from.group())));
                    return;
                }
                payTo(sender, from, value, user, new Key(user.uuid(), to));
            });
        });
        return Cmds.OK;
    }

    private void payTo(Player sender, Key from, BigDecimal value, AtriumUser user, Key to) {
        if (from.id().equals(user.uuid())) { send(sender, "self"); return; }
        if (value.compareTo(confirmationThreshold) > 0) {
            PendingPayment previous = confirmations.remove(from.id());
            long now = System.nanoTime();
            if (previous == null || !previous.from().equals(from) || !previous.target().equals(to)
                    || previous.amount().compareTo(value) != 0 || previous.expires() < now) {
                confirmations.put(from.id(), new PendingPayment(from, to, value, now + TimeUnit.SECONDS.toNanos(30)));
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
        observe(store.transferTaxed(from, to, received, taxAmount, "pay:" + from.id())
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
        String tail = Cmds.hasArgument(c, "amount") ? StringArgumentType.getString(c, "amount")
                : Cmds.hasArgument(c, "options") ? StringArgumentType.getString(c, "options") : "";
        Selected selected = select(c, tail);
        if (action.equals("log") && !selected.rest().isEmpty()) throw ctx.commands().fail(c.getSource(), "economy.group-syntax");
        if (!action.equals("log") && !action.equals("reset") && selected.rest().isEmpty()) {
            send(sender, "usage");
            return Cmds.OK;
        }
        AmountSyntax.Expression expression = action.equals("log") || action.equals("reset") ? null : expression(c, selected.rest());
        if (action.equals("reset") && !selected.rest().isEmpty()) expression(c, selected.rest()); // validate the optional argument
        String reason = "eco:" + action + ":" + sender.getName();
        String fromSender = senderGroup(sender);
        resolve(sender, name, user -> {
            if (action.equals("log")) {
                // Without a selector the log shows every group, each line tagged with its group.
                observe(store.history(user.uuid(), selected.key()).thenAccept(entries -> {
                    if (entries.isEmpty()) send(sender, "empty");
                    boolean tagged = store.worldGroups().enabled();
                    for (var entry : entries) send(sender, tagged ? "log-group" : "log",
                            Msg.unparsed("time", Instant.ofEpochMilli(entry.created()).toString()),
                            Msg.unparsed("amount", entry.amount()), Msg.unparsed("balance", entry.balance()),
                            Msg.unparsed("reason", entry.reason()), Msg.unparsed("group", groupName(entry.group())));
                }).exceptionally(failure -> { failed(sender, failure); return null; }));
                return;
            }
            Key key = new Key(user.uuid(), selected.key() != null ? selected.key() : targetGroup(fromSender, user.uuid()));
            if (action.equals("reset")) {
                applyAdmin(sender, key, action, store.startingBalance(), reason);
                return;
            }
            withAmount(sender, key, expression, value -> applyAdmin(sender, key, action, value, reason));
        });
        return Cmds.OK;
    }

    private void applyAdmin(CommandSender sender, Key key, String action, BigDecimal value, String reason) {
        CompletableFuture<TransactionResult> operation = switch (action) {
            case "give" -> store.deposit(key, value, reason);
            case "take" -> store.withdraw(key, value, reason);
            default -> store.set(key, value, reason);
        };
        observe(operation.thenAccept(result -> result(sender, result)));
    }
    /** {@code page} 0 means "take the page from the options", for "/baltop g:x 2". */
    private int top(CommandContext<CommandSourceStack> c, int page, String options) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Selected selected = select(c, options);
        if (page == 0) {
            page = 1;
            if (!selected.rest().isEmpty()) {
                try {
                    page = Integer.parseInt(selected.rest());
                } catch (NumberFormatException e) {
                    throw ctx.commands().fail(c.getSource(), "economy.group-syntax");
                }
                if (page < 1) throw ctx.commands().fail(c.getSource(), "economy.group-syntax");
            }
        } else if (!selected.rest().isEmpty()) {
            throw ctx.commands().fail(c.getSource(), "economy.group-syntax");
        }
        String group = selected.key() != null ? selected.key() : senderGroup(sender);
        List<EconomyStore.Entry> snapshot = leaders.getOrDefault(group, List.of());
        long start = (long) (page - 1) * config.pageSize;
        if (start >= snapshot.size()) { send(sender, "empty"); return Cmds.OK; }
        if (store.worldGroups().enabled()) {
            send(sender, "top-header-group", Msg.unparsed("page", Integer.toString(page)), Msg.unparsed("group", groupName(group)));
        } else {
            send(sender, "top-header", Msg.unparsed("page", Integer.toString(page)));
        }
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
        Key account = new Key(owner, senderGroup(player));
        withAmount(player, account, expression, value -> {
            if (value.signum() <= 0 || value.compareTo(chequeMaximum) > 0) {
                send(player, "cheque-amount", Msg.unparsed("max", store.format(chequeMaximum)));
                return;
            }
            String id = UUID.randomUUID().toString();
            observe(store.createCheque(account, id, value).thenAccept(result -> {
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
        // The note pays into the group of the world the player stands in. Unless cross-group payments are
        // allowed, a note written in another group is left unclaimed and stays valid there.
        String group = senderGroup(player);
        observe(store.claimCheque(id, owner, store.worldGroups().allowCrossGroupPay() ? null : group).thenCompose(claim -> {
            if (claim.status() == EconomyStore.Claim.WRONG_GROUP) {
                send(player, "cheque-wrong-group", Msg.unparsed("group", groupName(claim.group())));
                return CompletableFuture.completedFuture(null);
            }
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
            return store.deposit(new Key(owner, group), claim.value(), "cheque:redeem:" + id).thenApply(deposit -> {
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
    private void showBalance(CommandSender sender, Key key, String name) {
        boolean tagged = store.worldGroups().enabled();
        observe(store.balance(key).thenAccept(value -> send(sender, tagged ? "balance-group" : "balance",
                        Msg.unparsed("player", name), Msg.unparsed("amount", store.format(value)),
                        Msg.unparsed("group", groupName(key.group()))))
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
        store.leaderboards(excluded, inactiveSince).whenComplete((snapshot, failure) -> {
            if (failure != null) ctx.logger().warn("Cannot refresh economy leaderboard; retaining previous snapshot", failure);
            else leaders = snapshot;
            refreshing.set(false);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR) public void preLogin(AsyncPlayerPreLoginEvent event) {
        UUID id = event.getUniqueId();
        // Warming here is intentional: this thread already blocks for the core user data, and the player
        // is not in game yet, so every Vault read for this account is answered from a cached value later.
        // The world is unknown before login, so every group is loaded (one query). The values wait in the
        // bounded read cache, so a denied login cannot leak an online entry.
        try { store.warm(id).get(config.vaultTimeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.logger().warn("Interrupted while warming the economy balance for {}", id, e);
        } catch (ExecutionException | TimeoutException e) {
            ctx.logger().warn("Cannot warm the economy balance for {} before login; the first read may be deferred", id, e);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        // Fallback for a warm that failed or timed out before login; it also publishes the committed values.
        Player player = event.getPlayer();
        observe(store.online(player.getUniqueId(), player.getWorld().getName()));
    }
    @EventHandler(priority = EventPriority.MONITOR) public void changedWorld(PlayerChangedWorldEvent event) {
        // Every group is cached while online, so switching the context group needs no storage access.
        Player player = event.getPlayer();
        store.moved(player.getUniqueId(), player.getWorld().getName());
    }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        confirmations.remove(id);
        // Eviction waits for queued writes, so a commit cannot repopulate the cache after the player left.
        observe(store.offline(id));
    }
}
