package dev.yanianz.atrium.module.commandrules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionDefault;
import org.spongepowered.configurate.ConfigurateException;

/**
 * Global per-command costs, cooldowns, warmups, blocked worlds and extra permissions.
 *
 * <p>Enforcement happens at {@link EventPriority#HIGHEST} on {@link PlayerCommandPreprocessEvent}.
 * Charges and warmups cancel the event and re-dispatch the command only after the checks and the payment
 * succeeded, so a command can never run for free when the player cannot pay. Aliases and namespaced
 * labels are canonicalised through the server command map, which also prevents charging twice through
 * an alias and bypassing a cooldown with {@code /minecraft:command}.
 */
public final class CommandRulesModule implements AtriumModule, Listener {
    static final String PERMISSION_ADMIN = "atrium.commandrules.admin";
    static final String PERMISSION_BYPASS = "atrium.commandrules.bypass";
    static final String COOLDOWN_PERMISSION_PREFIX = "atrium.cmdcooldown.";
    static final String COOLDOWN_KEY_PREFIX = "commandrules:";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<CommandRulesConfig> config;
    private volatile RuleSet rules = RuleSet.empty();
    private volatile int confirmationSeconds = 15;

    private final Map<UUID, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    /** Marks the synchronous performCommand call, so a re-dispatched command is never charged twice. */
    private final Set<UUID> dispatching = ConcurrentHashMap.newKeySet();
    /** Label -> canonical root. Cleared on reload; the command map only changes on a server restart. */
    private final Map<String, String> canonicalCache = new ConcurrentHashMap<>();
    /** Alias index built from the command map plus the Brigadier tree. Rebuilt with the config. */
    private volatile CommandAliases aliases = CommandAliases.build(List.of(), Map.of());
    /** The live Brigadier dispatcher, captured through the public lifecycle API. */
    private volatile CommandDispatcher<CommandSourceStack> dispatcher;

    private record Confirmation(String canonical, long expires) {}

    private static final class Warmup {
        final CommandRule rule;
        final String canonical;
        final String line;
        WarmupState state;
        volatile TaskHandle task = TaskHandle.NOOP;

        Warmup(CommandRule rule, String canonical, String line) {
            this.rule = rule;
            this.canonical = canonical;
            this.line = line;
            this.state = WarmupState.start(rule.warmupSeconds());
        }
    }

    @Override
    public String id() {
        return "commandrules";
    }

    @Override
    public String description() {
        return "Per-command costs, cooldowns, warmups, world rules and extra permissions";
    }

    @Override
    public Set<String> softDependencies() {
        // Money rules use EconomyService when the economy module is available.
        return Set.of("economy");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(CommandRulesConfig.class, CommandRulesConfig::new);
        ctx.permission(PERMISSION_ADMIN, PermissionDefault.OP,
                "List, reload and inspect command rules");
        ctx.permission(PERMISSION_BYPASS, PermissionDefault.OP,
                "Bypass every command cost, cooldown and warmup");
        applyConfig();
        ctx.listen(this);
        registerCommands();
        captureDispatcher();
        // Paper registers Brigadier commands after plugin enable; re-resolve aliases once the command map
        // is complete so the per-command bypass permissions are registered under canonical roots too.
        ctx.track(ctx.scheduler().globalLater(() -> applyConfig(false), 2L));
    }

    @Override
    public void reload() {
        applyConfig();
    }

    @Override
    public void disable() {
        cancelWarmups();
        confirmations.clear();
        dispatching.clear();
    }

    // ---- configuration ----

    private void applyConfig() {
        applyConfig(true);
    }

    private void applyConfig(boolean log) {
        CommandRulesConfig cfg = config.get();
        confirmationSeconds = cfg.confirmationSeconds < 1 ? 15 : Math.min(cfg.confirmationSeconds, 300);
        canonicalCache.clear();
        aliases = buildAliases();
        RuleSet loaded = RuleSet.build(cfg.rules, this::canonical,
                problem -> ctx.logger().warn("Command rule skipped: {}", problem));
        rules = loaded;
        for (CommandRule rule : loaded.all()) {
            ctx.permission(PERMISSION_BYPASS + "." + rule.root(), PermissionDefault.OP,
                    "Bypass the command rule for /" + rule.root());
            String display = displayRoot(rule);
            if (!display.equals(rule.root())) {
                ctx.permission(PERMISSION_BYPASS + "." + display, PermissionDefault.OP,
                        "Bypass the command rule for /" + display);
            }
        }
        cancelWarmups();
        confirmations.clear();
        if (log) {
            ctx.logger().info("Loaded {} command rule(s)", loaded.size());
        }
    }

    private String displayRoot(CommandRule rule) {
        return canonical(rule.configuredRoot());
    }

    // ---- command-map resolution ----

    /** Resolves a typed label (alias, name, namespaced name) to the command root used as rule key. */
    private String canonical(String label) {
        String value = CommandLabels.root(label);
        if (value.isEmpty()) {
            return "";
        }
        String cached = canonicalCache.get(value);
        if (cached != null) {
            return cached;
        }
        String resolved = resolveCanonical(value);
        if (canonicalCache.size() >= 512) {
            canonicalCache.clear();
        }
        canonicalCache.put(value, resolved);
        return resolved;
    }

    private String resolveCanonical(String value) {
        // strip the namespace first: the command map and the Brigadier tree only know bare literals.
        return aliases.resolve(CommandLabels.stripNamespace(value));
    }

    /**
     * Builds the alias index from every command-map entry plus the Brigadier redirects. Paper registers
     * plugin aliases as separate wrappers with an empty alias list, but the main wrapper declares them,
     * so reading every command's aliases (without a length filter) finds {@code bal -> balance}.
     */
    private CommandAliases buildAliases() {
        List<CommandAliases.Known> known = new ArrayList<>();
        CommandMap map = Bukkit.getCommandMap();
        if (map != null) {
            try {
                for (Map.Entry<String, Command> entry : map.getKnownCommands().entrySet()) {
                    Command command = entry.getValue();
                    if (command == null) {
                        continue;
                    }
                    known.add(new CommandAliases.Known(entry.getKey(), command.getName(),
                            command.getAliases(), command));
                }
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot read the server command map; command aliases may not resolve", e);
            }
        }
        return CommandAliases.build(known, brigadierRedirects());
    }

    /** Captures the live dispatcher through Paper's public lifecycle API, if the event has not fired yet. */
    private void captureDispatcher() {
        try {
            ctx.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                    event -> dispatcher = event.registrar().getDispatcher());
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot observe the Brigadier command tree; vanilla redirects may not resolve", e);
        }
    }

    private Map<String, String> brigadierRedirects() {
        CommandDispatcher<CommandSourceStack> current = dispatcher;
        if (current == null) {
            return Map.of();
        }
        Map<String, String> redirects = new LinkedHashMap<>();
        try {
            for (CommandNode<CommandSourceStack> child : current.getRoot().getChildren()) {
                if (!(child instanceof LiteralCommandNode<CommandSourceStack> literal)) {
                    continue;
                }
                CommandNode<CommandSourceStack> target = literal.getRedirect();
                if (target == null) {
                    continue;
                }
                String root = followRedirect(target);
                if (!root.isEmpty()) {
                    redirects.put(literal.getLiteral(), root);
                }
            }
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot read Brigadier redirects; vanilla aliases may not resolve", e);
        }
        return Map.copyOf(redirects);
    }

    private static String followRedirect(CommandNode<CommandSourceStack> start) {
        CommandNode<CommandSourceStack> current = start;
        Set<CommandNode<CommandSourceStack>> seen = new HashSet<>();
        String root = "";
        while (current != null && seen.add(current)) {
            if (current instanceof LiteralCommandNode<CommandSourceStack> literal) {
                root = literal.getLiteral();
            }
            current = current.getRedirect();
        }
        return CommandLabels.stripNamespace(root.toLowerCase(Locale.ROOT));
    }

    private Command resolve(String label) {
        CommandMap map = Bukkit.getCommandMap();
        if (map == null) {
            return null;
        }
        Map<String, Command> known = map.getKnownCommands();
        String value = CommandLabels.root(label);
        String bare = CommandLabels.stripNamespace(value);
        Command command = known.get(value);
        if (command == null) {
            command = known.get(bare);
        }
        if (command == null && !value.contains(":")) {
            command = known.get("minecraft:" + bare);
        }
        return command;
    }

    /**
     * Best-effort check of the command's own permission before money is withdrawn. Paper's Brigadier
     * permissions are not always exposed through the Bukkit wrapper, so an unknown permission means
     * "let the command decide"; a declared one is honoured before charging.
     */
    private boolean commandPermissionAllows(Player player, String typedRoot, String canonical) {
        Command command = resolve(typedRoot);
        if (command == null) {
            command = resolve(canonical);
        }
        if (command == null) {
            return true;
        }
        String permission = command.getPermission();
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private boolean commandExists(String typedRoot, String canonical) {
        return resolve(typedRoot) != null || resolve(canonical) != null;
    }

    // ---- enforcement ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!config.get().enabled || rules.size() == 0) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (dispatching.contains(uuid)) {
            // Re-dispatch of a command that was already checked, warmed up and paid for.
            return;
        }

        String line = event.getMessage();
        String typedRoot = CommandLabels.root(line);
        if (typedRoot.isEmpty()) {
            return;
        }
        String canonical = canonical(typedRoot);

        // Any new command replaces a warmup that is still counting down.
        Warmup previous = warmups.remove(uuid);
        if (previous != null) {
            previous.task.cancel();
            send(player, "warmup-cancelled", Msg.unparsed("command", previous.canonical));
        }

        CommandRule rule = rules.find(canonical, this::canonical).orElse(null);
        if (rule == null) {
            return;
        }
        if (bypassed(player, canonical)) {
            return;
        }
        if (!commandExists(typedRoot, canonical)) {
            // Never charge for a label the server does not know; let it report the unknown command.
            return;
        }
        if (!rule.permission().isEmpty() && !player.hasPermission(rule.permission())) {
            event.setCancelled(true);
            send(player, "no-permission", Msg.unparsed("command", canonical),
                    Msg.unparsed("permission", rule.permission()));
            return;
        }
        if (!commandPermissionAllows(player, typedRoot, canonical)) {
            event.setCancelled(true);
            send(player, "no-permission-command", Msg.unparsed("command", canonical));
            return;
        }
        if (rule.blocksWorld(player.getWorld().getName())) {
            event.setCancelled(true);
            send(player, "blocked-world", Msg.unparsed("command", canonical),
                    Msg.unparsed("world", player.getWorld().getName()));
            return;
        }

        Duration left = cooldownRemaining(player, rule, canonical);
        if (!left.isZero()) {
            event.setCancelled(true);
            send(player, "cooldown", Msg.unparsed("command", canonical), Msg.duration("time", left));
            return;
        }

        if (rule.hasCost() && rule.confirmCost() && !consumeConfirmation(player, canonical)) {
            event.setCancelled(true);
            rememberConfirmation(player, canonical);
            send(player, "cost-confirm", Msg.unparsed("command", canonical),
                    Msg.unparsed("cost", costSummary(rule)), Msg.number("seconds", confirmationSeconds));
            return;
        }

        if (rule.warmupSeconds() > 0) {
            event.setCancelled(true);
            startWarmup(player, rule, canonical, line);
            return;
        }

        if (rule.hasCost()) {
            event.setCancelled(true);
            execute(player, rule, canonical, line);
            return;
        }

        // No charge: start the cooldown and let the original event continue.
        Duration acquired = acquireCooldown(player, rule, canonical);
        if (!acquired.isZero()) {
            event.setCancelled(true);
            send(player, "cooldown", Msg.unparsed("command", canonical), Msg.duration("time", acquired));
        }
    }

    private boolean bypassed(Player player, String canonical) {
        return player.hasPermission(PERMISSION_BYPASS)
                || player.hasPermission(PERMISSION_BYPASS + "." + canonical);
    }

    private int effectiveCooldownSeconds(Player player, CommandRule rule, String canonical) {
        if (rule.cooldownSeconds() == 0) {
            return 0;
        }
        OptionalInt override = dev.yanianz.atrium.core.permission.Permissions.highestNumeric(
                player, COOLDOWN_PERMISSION_PREFIX + canonical);
        return CooldownPolicy.effectiveSeconds(rule.cooldownSeconds(), override);
    }

    private Duration cooldownRemaining(Player player, CommandRule rule, String canonical) {
        int seconds = effectiveCooldownSeconds(player, rule, canonical);
        AtriumUser user = user(player);
        if (seconds == 0 || user == null) {
            return Duration.ZERO;
        }
        return ctx.cooldowns().remaining(user, COOLDOWN_KEY_PREFIX + canonical);
    }

    private Duration acquireCooldown(Player player, CommandRule rule, String canonical) {
        int seconds = effectiveCooldownSeconds(player, rule, canonical);
        AtriumUser user = user(player);
        if (seconds == 0 || user == null) {
            return Duration.ZERO;
        }
        return ctx.cooldowns().tryAcquire(user, COOLDOWN_KEY_PREFIX + canonical, CooldownPolicy.duration(seconds));
    }

    private void clearCooldown(Player player, String canonical) {
        AtriumUser user = user(player);
        if (user != null) {
            ctx.cooldowns().clear(user, COOLDOWN_KEY_PREFIX + canonical);
        }
    }

    private AtriumUser user(Player player) {
        return ctx.users().cached(player.getUniqueId()).orElse(null);
    }

    // ---- charging and dispatch ----

    /**
     * Starts the cooldown, charges exactly once and only then runs the command. The player thread is the
     * only thread that touches the player; the economy write happens asynchronously.
     *
     * <p>When the command refuses to run ({@code performCommand} returns false) or throws, the money and
     * the experience levels are refunded. A command that starts but fails internally cannot be detected;
     * that is what {@code charge-after} exists for.
     */
    private void execute(Player player, CommandRule rule, String canonical, String line) {
        if (!player.isOnline()) {
            return;
        }
        Duration left = acquireCooldown(player, rule, canonical);
        if (!left.isZero()) {
            send(player, "cooldown", Msg.unparsed("command", canonical), Msg.duration("time", left));
            return;
        }
        if (rule.expLevels() > 0 && player.getLevel() < rule.expLevels()) {
            clearCooldown(player, canonical);
            send(player, "insufficient-exp", Msg.unparsed("command", canonical),
                    Msg.number("levels", rule.expLevels()));
            return;
        }
        if (config.get().chargeAfter) {
            executeChargeAfter(player, rule, canonical, line);
            return;
        }
        if (!rule.hasMoneyCost()) {
            if (!takeLevels(player, rule)) {
                clearCooldown(player, canonical);
                send(player, "insufficient-exp", Msg.unparsed("command", canonical),
                        Msg.number("levels", rule.expLevels()));
                return;
            }
            if (!dispatch(player, line)) {
                refundAfterFailure(player, rule, canonical, null);
            }
            return;
        }

        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            clearCooldown(player, canonical);
            ctx.logger().warn("Cannot charge {} for /{}: EconomyService is not available", rule.cost(), canonical);
            send(player, "economy-missing", Msg.unparsed("command", canonical));
            return;
        }
        UUID uuid = player.getUniqueId();
        economy.withdraw(uuid, rule.cost(), "command:" + canonical)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        ctx.logger().error("Withdrawing {} for /{} failed", rule.cost(), canonical, error);
                    }
                    ctx.scheduler().entity(player,
                            () -> charged(player, rule, canonical, line, economy, result, error),
                            () -> {
                                if (result != null && result.success()) {
                                    refund(economy, uuid, rule, canonical, "player left before dispatch");
                                }
                            });
                });
    }

    private void charged(Player player, CommandRule rule, String canonical, String line, EconomyService economy,
            TransactionResult result, Throwable error) {
        if (!player.isOnline()) {
            if (result != null && result.success()) {
                refund(economy, player.getUniqueId(), rule, canonical, "player left before dispatch");
            }
            return;
        }
        if (error != null || result == null || !result.success()) {
            clearCooldown(player, canonical);
            if (result != null && result.status() == TransactionResult.Status.INSUFFICIENT_FUNDS) {
                send(player, "insufficient-money", Msg.unparsed("command", canonical),
                        Msg.unparsed("cost", moneyText(rule)));
            } else {
                send(player, "charge-failed", Msg.unparsed("command", canonical));
            }
            return;
        }
        if (!takeLevels(player, rule)) {
            refund(economy, player.getUniqueId(), rule, canonical, "not enough experience levels");
            clearCooldown(player, canonical);
            send(player, "insufficient-exp", Msg.unparsed("command", canonical),
                    Msg.number("levels", rule.expLevels()));
            return;
        }
        if (!dispatch(player, line)) {
            refundAfterFailure(player, rule, canonical, economy);
        }
    }

    /**
     * Opt-in {@code charge-after} mode: the balance is only checked up front (never withdrawn), the command
     * runs, and only a command that actually started is charged. A command that runs but fails internally
     * still cannot be detected.
     */
    private void executeChargeAfter(Player player, CommandRule rule, String canonical, String line) {
        if (!rule.hasMoneyCost()) {
            if (!dispatch(player, line)) {
                return;
            }
            if (!takeLevels(player, rule)) {
                send(player, "insufficient-exp", Msg.unparsed("command", canonical),
                        Msg.number("levels", rule.expLevels()));
            }
            return;
        }
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            clearCooldown(player, canonical);
            ctx.logger().warn("Cannot charge {} for /{}: EconomyService is not available", rule.cost(), canonical);
            send(player, "economy-missing", Msg.unparsed("command", canonical));
            return;
        }
        UUID uuid = player.getUniqueId();
        economy.balance(uuid).whenComplete((balance, error) -> ctx.scheduler().entity(player,
                () -> afterBalanceCheck(player, rule, canonical, line, economy, balance, error), null));
    }

    private void afterBalanceCheck(Player player, CommandRule rule, String canonical, String line,
            EconomyService economy, java.math.BigDecimal balance, Throwable error) {
        if (!player.isOnline()) {
            return;
        }
        if (error != null || balance == null) {
            clearCooldown(player, canonical);
            ctx.logger().error("Cannot read the balance of {} for /{}", player.getName(), canonical, error);
            send(player, "charge-failed", Msg.unparsed("command", canonical));
            return;
        }
        if (balance.compareTo(rule.cost()) < 0) {
            clearCooldown(player, canonical);
            send(player, "insufficient-money", Msg.unparsed("command", canonical),
                    Msg.unparsed("cost", moneyText(rule)));
            return;
        }
        if (!dispatch(player, line)) {
            // Nothing was charged: in this mode payment happens after a successful dispatch.
            return;
        }
        if (!takeLevels(player, rule)) {
            send(player, "insufficient-exp", Msg.unparsed("command", canonical),
                    Msg.number("levels", rule.expLevels()));
            return;
        }
        UUID uuid = player.getUniqueId();
        economy.withdraw(uuid, rule.cost(), "command:" + canonical).whenComplete((result, failure) -> {
            if (failure == null && result != null && result.success()) {
                return;
            }
            if (failure != null) {
                ctx.logger().error("Withdrawing {} for /{} after dispatch failed", rule.cost(), canonical, failure);
            } else {
                ctx.logger().error("Withdrawing {} for /{} after dispatch failed: {}", rule.cost(), canonical,
                        result == null ? "no result" : result.status());
            }
            ctx.scheduler().entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                restoreLevels(player, rule);
                send(player, "charge-after-failed", Msg.unparsed("command", canonical));
            }, null);
        });
    }

    private boolean takeLevels(Player player, CommandRule rule) {
        if (rule.expLevels() <= 0) {
            return true;
        }
        int level = player.getLevel();
        if (level < rule.expLevels()) {
            return false;
        }
        player.setLevel(level - rule.expLevels());
        return true;
    }

    private void restoreLevels(Player player, CommandRule rule) {
        if (rule.expLevels() > 0) {
            player.setLevel(player.getLevel() + rule.expLevels());
        }
    }

    /** Gives back what a charged command received before it refused to run. */
    private void refundAfterFailure(Player player, CommandRule rule, String canonical, EconomyService economy) {
        restoreLevels(player, rule);
        if (rule.hasMoneyCost() && economy != null) {
            refund(economy, player.getUniqueId(), rule, canonical, "command failed");
        }
        if (config.get().refundOnFailureMessage) {
            send(player, "refunded", Msg.unparsed("command", canonical),
                    Msg.unparsed("cost", costSummary(rule)));
        }
    }

    private void refund(EconomyService economy, UUID uuid, CommandRule rule, String canonical, String reason) {
        if (!rule.hasMoneyCost() || economy == null) {
            return;
        }
        economy.deposit(uuid, rule.cost(), "command-refund:" + canonical).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Refund of {} for /{} failed ({})", rule.cost(), canonical, reason, error);
            } else if (result != null && !result.success()) {
                ctx.logger().error("Refund of {} for /{} failed ({}): {}", rule.cost(), canonical, reason,
                        result.status());
            }
        });
    }

    /** Runs the command on the player's own thread; returns false when it refused to run or threw. */
    private boolean dispatch(Player player, String line) {
        if (!player.isOnline()) {
            return false;
        }
        String command = line.strip();
        while (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isEmpty()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        boolean success;
        dispatching.add(uuid);
        try {
            success = player.performCommand(command);
        } catch (RuntimeException e) {
            ctx.logger().error("Running charged command '/{}' failed for {}", command, player.getName(), e);
            send(player, "command-failed", Msg.unparsed("command", CommandLabels.root(command)));
            return false;
        } finally {
            dispatching.remove(uuid);
        }
        if (!success) {
            ctx.logger().warn("Charged command '/{}' did not run for {}", command, player.getName());
            send(player, "command-failed", Msg.unparsed("command", CommandLabels.root(command)));
        }
        return success;
    }

    // ---- confirmations ----

    private boolean consumeConfirmation(Player player, String canonical) {
        UUID uuid = player.getUniqueId();
        Confirmation pending = confirmations.get(uuid);
        if (pending == null) {
            return false;
        }
        if (pending.expires() < System.currentTimeMillis() || !pending.canonical().equals(canonical)) {
            confirmations.remove(uuid, pending);
            return false;
        }
        return confirmations.remove(uuid, pending);
    }

    private void rememberConfirmation(Player player, String canonical) {
        confirmations.put(player.getUniqueId(),
                new Confirmation(canonical, System.currentTimeMillis() + confirmationSeconds * 1000L));
    }

    // ---- warmups ----

    private void startWarmup(Player player, CommandRule rule, String canonical, String line) {
        UUID uuid = player.getUniqueId();
        Warmup warmup = new Warmup(rule, canonical, line);
        warmups.put(uuid, warmup);
        send(player, "warmup-started", Msg.unparsed("command", canonical),
                Msg.number("seconds", rule.warmupSeconds()));
        if (config.get().warmupCountdownActionBar) {
            ctx.messages().actionBar(player, "commandrules.warmup-countdown",
                    Msg.unparsed("command", canonical), Msg.number("seconds", rule.warmupSeconds()));
        }
        warmup.task = ctx.scheduler().entityTimer(player, () -> tickWarmup(player, warmup),
                () -> warmups.remove(uuid, warmup), 20L, 20L);
    }

    private void tickWarmup(Player player, Warmup warmup) {
        if (warmups.get(player.getUniqueId()) != warmup) {
            warmup.task.cancel();
            return;
        }
        WarmupState next = warmup.state.tick();
        warmup.state = next;
        if (next.phase() == WarmupState.Phase.COMPLETED) {
            if (warmups.remove(player.getUniqueId(), warmup)) {
                warmup.task.cancel();
                execute(player, warmup.rule, warmup.canonical, warmup.line);
            }
            return;
        }
        if (next.active() && config.get().warmupCountdownActionBar) {
            ctx.messages().actionBar(player, "commandrules.warmup-countdown",
                    Msg.unparsed("command", warmup.canonical), Msg.number("seconds", next.remainingSeconds()));
        }
    }

    private void cancelWarmup(Player player, String messageKey) {
        Warmup warmup = warmups.remove(player.getUniqueId());
        if (warmup != null) {
            warmup.task.cancel();
            if (messageKey != null) {
                send(player, messageKey, Msg.unparsed("command", warmup.canonical));
            }
        }
    }

    private void cancelWarmups() {
        for (Warmup warmup : warmups.values()) {
            warmup.task.cancel();
        }
        warmups.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (config.get().cancelWarmupOnMove && event.hasChangedBlock()) {
            cancelWarmup(event.getPlayer(), "warmup-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (config.get().cancelWarmupOnMove) {
            cancelWarmup(event.getPlayer(), "warmup-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (config.get().cancelWarmupOnDamage && event.getEntity() instanceof Player player) {
            cancelWarmup(player, "warmup-cancelled-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Warmup warmup = warmups.remove(uuid);
        if (warmup != null) {
            warmup.task.cancel();
        }
        confirmations.remove(uuid);
    }

    // ---- /commandrules ----

    private void registerCommands() {
        var check = Commands.literal("check")
                .executes(context -> { throw cmds.fail(context.getSource(), "commandrules.usage"); })
                .then(Commands.argument("command", CommandLabelArgument.label())
                        .suggests((context, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            for (CommandRule rule : rules.all()) {
                                if (rule.root().startsWith(remaining)) {
                                    builder.suggest(rule.root());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> check(context, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(context -> check(context,
                                        StringArgumentType.getString(context, "player")))));
        var root = Cmds.literal("commandrules", PERMISSION_ADMIN)
                .executes(context -> {
                    throw cmds.fail(context.getSource(), "commandrules.usage");
                })
                .then(Cmds.literal("list").executes(this::list))
                .then(Cmds.literal("reload").executes(this::reloadCommand))
                .then(check);
        ctx.command(CommandSpec.of(root.build(),
                "Per-command costs, cooldowns, warmups, world rules and extra permissions", "crule"));
    }

    private int list(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<CommandRule> all = new ArrayList<>(rules.all());
        send(sender, "list-header", Msg.number("count", all.size()));
        if (all.isEmpty()) {
            send(sender, "list-empty");
            return Cmds.OK;
        }
        for (CommandRule rule : all) {
            send(sender, "list-entry",
                    Msg.unparsed("command", displayRoot(rule)),
                    Msg.unparsed("cost", rule.hasMoneyCost() ? moneyText(rule) : word(sender, "value-none")),
                    Msg.number("levels", rule.expLevels()),
                    Msg.unparsed("cooldown", CooldownPolicy.display(rule.cooldownSeconds(),
                            word(sender, "value-none"), word(sender, "value-lifetime"))),
                    Msg.number("warmup", rule.warmupSeconds()),
                    Msg.unparsed("worlds", rule.blockedWorlds().isEmpty()
                            ? word(sender, "value-all") : String.join(", ", rule.blockedWorlds())),
                    Msg.unparsed("permission", rule.permission().isEmpty()
                            ? word(sender, "value-none") : rule.permission()),
                    Msg.unparsed("confirm", word(sender, rule.confirmCost() ? "value-yes" : "value-no")));
        }
        return Cmds.OK;
    }

    private int reloadCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        try {
            config.reload();
        } catch (ConfigurateException e) {
            ctx.logger().error("Cannot reload commandrules.yml", e);
            throw cmds.fail(context.getSource(), "commandrules.reload-failed",
                    Msg.unparsed("error", String.valueOf(e.getMessage())));
        }
        applyConfig();
        send(context.getSource().getSender(), "reloaded", Msg.number("count", rules.size()));
        return Cmds.OK;
    }

    private int check(CommandContext<CommandSourceStack> context, String playerName) {
        CommandSender sender = context.getSource().getSender();
        String typed = StringArgumentType.getString(context, "command");
        String canonical = canonical(typed);
        send(sender, "check-header", Msg.unparsed("command", typed), Msg.unparsed("canonical", canonical));

        CommandRule rule = rules.find(canonical, this::canonical).orElse(null);
        if (rule == null) {
            send(sender, "check-no-rule", Msg.unparsed("command", typed));
            return Cmds.OK;
        }
        send(sender, "check-rule",
                Msg.unparsed("command", canonical),
                Msg.unparsed("cost", rule.hasMoneyCost() ? moneyText(rule) : word(sender, "value-none")),
                Msg.number("levels", rule.expLevels()),
                Msg.unparsed("cooldown", CooldownPolicy.display(rule.cooldownSeconds(),
                        word(sender, "value-none"), word(sender, "value-lifetime"))),
                Msg.number("warmup", rule.warmupSeconds()),
                Msg.unparsed("worlds", rule.blockedWorlds().isEmpty()
                        ? word(sender, "value-all") : String.join(", ", rule.blockedWorlds())),
                Msg.unparsed("permission", rule.permission().isEmpty()
                        ? word(sender, "value-none") : rule.permission()),
                Msg.unparsed("confirm", word(sender, rule.confirmCost() ? "value-yes" : "value-no")));

        if (playerName == null) {
            return Cmds.OK;
        }
        // Resolve the sender's locale on its own thread; the load below completes on an I/O thread.
        String none = word(sender, "value-none");
        String ready = word(sender, "value-ready");
        String unknown = word(sender, "value-unknown");
        String yes = word(sender, "value-yes");
        String no = word(sender, "value-no");
        ctx.users().loadByName(playerName).thenAccept(found -> {
            if (found.isEmpty()) {
                sendLaterRaw(sender, "core.user-not-found", Msg.unparsed("name", playerName));
                return;
            }
            AtriumUser user = found.get();
            Player online = Bukkit.getPlayer(user.uuid());
            if (online == null) {
                reportCheckPlayer(sender, rule, canonical, user, null, none, ready, unknown, yes, no);
                return;
            }
            // highestNumeric and hasPermission read the permission tree, which belongs to the target's
            // entity thread; the I/O callback must never touch it.
            ctx.scheduler().entity(online,
                    () -> reportCheckPlayer(sender, rule, canonical, user, online, none, ready, unknown, yes, no),
                    () -> reportCheckPlayer(sender, rule, canonical, user, null, none, ready, unknown, yes, no));
        }).exceptionally(error -> {
            ctx.logger().error("Could not load '{}' for /commandrules check", playerName, error);
            sendLater(sender, "check-player-failed", Msg.unparsed("player", playerName));
            return null;
        });
        return Cmds.OK;
    }

    /** Runs on the target's entity thread (or the sender thread when the target is offline). */
    private void reportCheckPlayer(CommandSender sender, CommandRule rule, String canonical, AtriumUser user,
            Player online, String none, String ready, String unknown, String yes, String no) {
        OptionalInt override = online == null ? OptionalInt.empty()
                : dev.yanianz.atrium.core.permission.Permissions.highestNumeric(
                        online, COOLDOWN_PERMISSION_PREFIX + canonical);
        int seconds = CooldownPolicy.effectiveSeconds(rule.cooldownSeconds(), override);
        Duration left = seconds == 0 ? Duration.ZERO
                : ctx.cooldowns().remaining(user, COOLDOWN_KEY_PREFIX + canonical);
        String cooldown = seconds == 0 ? none : left.isZero() ? ready : Durations.format(left, 3);
        String bypass = online == null ? unknown : bypassed(online, canonical) ? yes : no;
        String permission = rule.permission().isEmpty() ? none
                : online == null ? unknown
                : online.hasPermission(rule.permission()) ? yes : no;
        sendLater(sender, "check-player",
                Msg.unparsed("player", user.name()),
                Msg.unparsed("cooldown", cooldown),
                Msg.unparsed("bypass", bypass),
                Msg.unparsed("permission", permission));
    }

    // ---- text helpers ----

    private void send(CommandSender recipient, String key, TagResolver... resolvers) {
        ctx.messages().send(recipient, "commandrules." + key, resolvers);
    }

    private void sendLater(CommandSender recipient, String key, TagResolver... resolvers) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player,
                    () -> ctx.messages().send(player, "commandrules." + key, resolvers), null);
        } else {
            ctx.messages().send(recipient, "commandrules." + key, resolvers);
        }
    }

    /** Sends an already-namespaced key, e.g. a {@code core.*} message, without adding the module prefix. */
    private void sendLaterRaw(CommandSender recipient, String key, TagResolver... resolvers) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, resolvers), null);
        } else {
            ctx.messages().send(recipient, key, resolvers);
        }
    }

    private String word(CommandSender recipient, String key) {
        return PlainTextComponentSerializer.plainText()
                .serialize(ctx.messages().render(recipient, "commandrules." + key));
    }

    private String moneyText(CommandRule rule) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        return economy == null ? rule.cost().stripTrailingZeros().toPlainString() : economy.format(rule.cost());
    }

    private String costSummary(CommandRule rule) {
        if (rule.hasMoneyCost() && rule.expLevels() > 0) {
            return moneyText(rule) + " + " + rule.expLevels() + " XP levels";
        }
        if (rule.hasMoneyCost()) {
            return moneyText(rule);
        }
        return rule.expLevels() + " XP levels";
    }
}
