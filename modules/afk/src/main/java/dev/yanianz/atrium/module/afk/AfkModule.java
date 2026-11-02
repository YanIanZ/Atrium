package dev.yanianz.atrium.module.afk;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.afk.AfkReason;
import dev.yanianz.atrium.api.afk.AfkService;
import dev.yanianz.atrium.api.afk.AfkStateChangeEvent;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Automatic and manual AFK detection with configurable idle actions.
 *
 * <p>One global timer schedules a cheap check on each player's own thread. There is no per-player timer,
 * so quitting cannot leak tasks. Movement, chat, commands, interaction and inventory clicks count as
 * activity; small repeated movement inside a box is intentionally not enough.
 */
public final class AfkModule implements AtriumModule, Listener, AfkService {
    static final String PERM_AFK = "atrium.afk";
    static final String PERM_KICK_EXEMPT = "atrium.afk.kickexempt";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500));

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<AfkConfig> config;
    private AfkStore store;
    private volatile AfkRules rules;
    private final Map<UUID, AfkTracker> trackers = new ConcurrentHashMap<>();
    private TaskHandle checkTask = TaskHandle.NOOP;

    @Override
    public String id() {
        return "afk";
    }

    @Override
    public String description() {
        return "Automatic and manual AFK detection, broadcasts and idle actions";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("vanish");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.cmds = context.commands();
        this.config = context.config(AfkConfig.class, AfkConfig::new);
        validate(config.get());
        this.rules = AfkRules.from(config.get());

        ctx.migrate(AfkStore.MIGRATIONS);
        this.store = new AfkStore(ctx.database());
        ctx.component(store.component);
        ctx.service(AfkService.class, this);
        ctx.listen(this);

        ctx.permission(PERM_AFK, PermissionDefault.TRUE, "Toggle your own AFK state");
        ctx.permission(PERM_KICK_EXEMPT, PermissionDefault.OP, "Exempt from the AFK kick");

        ctx.command(CommandSpec.of(Cmds.literal("afk", PERM_AFK)
                .executes(this::toggle)
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::toggle))
                .build(), "Toggle AFK"));

        long now = System.currentTimeMillis();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            register(player, now);
        }
        scheduleChecks();
    }

    @Override
    public void disable() {
        checkTask.cancel();
        long now = System.currentTimeMillis();
        for (AfkTracker tracker : List.copyOf(trackers.values())) {
            if (!tracker.isAfk()) {
                continue;
            }
            Player player = Bukkit.getPlayer(tracker.uuid());
            AfkTracker.Exit exit = tracker.endAfk(now);
            if (player != null) {
                restoreLater(player, exit);
            }
            recordAfkSession(tracker.uuid(), exit.durationMillis());
        }
        trackers.clear();
    }

    private void restoreLater(Player player, AfkTracker.Exit exit) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            restore(player, exit);
            return;
        }
        try {
            ctx.scheduler().entity(player, () -> restore(player, exit), null);
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling the AFK cleanup of {} failed", player.getName(), e);
        }
    }

    @Override
    public void reload() throws Exception {
        validate(config.get());
        this.rules = AfkRules.from(config.get());
        scheduleChecks();
    }

    private void scheduleChecks() {
        checkTask.cancel();
        long ticks = Math.max(1, config.get().checkIntervalSeconds) * 20L;
        checkTask = ctx.track(ctx.scheduler().globalTimer(this::checkAll, ticks, ticks));
    }

    private void register(Player player, long now) {
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        trackers.put(player.getUniqueId(), new AfkTracker(player.getUniqueId(), now, AfkPosition.of(location)));
    }

    // ---- AfkService ----

    @Override
    public boolean isAfk(UUID player) {
        AfkTracker tracker = trackers.get(player);
        return tracker != null && tracker.isAfk();
    }

    @Override
    public Optional<Instant> afkSince(UUID player) {
        AfkTracker tracker = trackers.get(player);
        if (tracker == null || !tracker.isAfk()) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(tracker.afkSince()));
    }

    @Override
    public boolean setAfk(Player player, boolean afk, AfkReason reason) {
        if (player == null || reason == null || !player.isOnline()) {
            return false;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            return setAfkNow(player, afk, reason);
        }
        try {
            ctx.scheduler().entity(player, () -> setAfkNow(player, afk, reason), null);
            return true;
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling an AFK change for {} failed", player.getName(), e);
            return false;
        }
    }

    private boolean setAfkNow(Player player, boolean afk, AfkReason reason) {
        AfkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            return false;
        }
        if (tracker.isAfk() == afk) {
            return true;
        }
        return apply(player, tracker, afk, reason, "");
    }

    // ---- commands ----

    private int toggle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        AfkConfig cfg = config.get();
        String message = "";
        if (Cmds.hasArgument(c, "message")) {
            message = StringArgumentType.getString(c, "message").trim();
            if (message.length() > cfg.maxMessageLength) {
                throw cmds.fail(c.getSource(), "afk.message-too-long", Msg.number("max", cfg.maxMessageLength));
            }
        }
        AfkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            ctx.messages().send(player, "core.error");
            return Cmds.OK;
        }
        if (tracker.isAfk()) {
            if (apply(player, tracker, false, AfkReason.MANUAL, "")) {
                ctx.messages().send(player, "afk.toggle-off");
            } else {
                ctx.messages().send(player, "afk.cancelled");
            }
        } else if (apply(player, tracker, true, AfkReason.MANUAL, message)) {
            ctx.messages().send(player, "afk.toggle-on");
        } else {
            ctx.messages().send(player, "afk.cancelled");
        }
        return Cmds.OK;
    }

    // ---- state changes ----

    private boolean apply(Player player, AfkTracker tracker, boolean entering, AfkReason reason, String message) {
        if (!player.isOnline() || trackers.get(player.getUniqueId()) != tracker) {
            return false;
        }
        if (tracker.isAfk() == entering) {
            return true;
        }
        AfkStateChangeEvent event = new AfkStateChangeEvent(player, entering, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (reason == AfkReason.AUTOMATIC) {
                // Do not retry a rejected automatic transition every second.
                tracker.postpone(System.currentTimeMillis() + 30_000L);
            }
            return false;
        }

        long now = System.currentTimeMillis();
        AfkConfig cfg = config.get();
        if (entering) {
            boolean sleep = cfg.sleepingIgnoredWhileAfk;
            boolean invulnerable = cfg.invulnerableWhileAfk;
            boolean previousSleep = player.isSleepingIgnored();
            boolean previousInvulnerable = player.isInvulnerable();
            if (!tracker.beginAfk(now, reason, message, sleep, previousSleep, invulnerable, previousInvulnerable)) {
                return true;
            }
            if (sleep) {
                player.setSleepingIgnored(true);
            }
            if (invulnerable) {
                player.setInvulnerable(true);
            }
            broadcast(player, message, true, reason);
        } else {
            AfkTracker.Exit exit = tracker.endAfk(now);
            restore(player, exit);
            recordAfkSession(player.getUniqueId(), exit.durationMillis());
            broadcast(player, "", false, reason);
        }
        return true;
    }

    private void restore(Player player, AfkTracker.Exit exit) {
        if (exit.restoreSleepingIgnored()) {
            player.setSleepingIgnored(exit.previousSleepingIgnored());
        }
        if (exit.restoreInvulnerable()) {
            player.setInvulnerable(exit.previousInvulnerable());
        }
    }

    private void leaveIfAfk(Player player, AfkReason reason) {
        AfkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker != null && tracker.isAfk()) {
            apply(player, tracker, false, reason, "");
        }
    }

    private void broadcast(Player player, String message, boolean entering, AfkReason reason) {
        AfkConfig cfg = config.get();
        if (entering ? !cfg.broadcastOnEnter : !cfg.broadcastOnLeave) {
            return;
        }
        String key = entering
                ? (message.isBlank() ? "afk.broadcast-enter" : "afk.broadcast-enter-reason")
                : "afk.broadcast-leave";
        TagResolver[] resolvers = {
                Msg.unparsed("player", player.getName()),
                Msg.unparsed("message", message),
                Msg.unparsed("reason", reason.name().toLowerCase(Locale.ROOT))
        };
        Component rendered = ctx.messages().render(Bukkit.getConsoleSender(), key, resolvers);
        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        try {
            // A global broadcast avoids touching another player's region thread. Vanished staff still
            // receive the message when they are allowed to see the player.
            ctx.scheduler().global(() -> {
                Bukkit.getConsoleSender().sendMessage(rendered);
                for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
                    if (vanish != null && !vanish.canSee(online, player)) {
                        continue;
                    }
                    online.sendMessage(rendered);
                }
            });
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling the AFK broadcast for {} failed", player.getName(), e);
            Bukkit.getServer().sendMessage(rendered);
        }
    }

    private void recordAfkSession(UUID uuid, long durationMillis) {
        long seconds = durationMillis / 1000L;
        if (seconds <= 0) {
            return;
        }
        try {
            ctx.users().cached(uuid).ifPresent(user ->
                    user.update(store.component, profile -> profile.plus(seconds)));
        } catch (RuntimeException e) {
            ctx.logger().warn("Recording the AFK session of {} failed", uuid, e);
        }
    }

    // ---- idle loop ----

    private void checkAll() {
        trackers.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            AfkTracker tracker = trackers.get(player.getUniqueId());
            if (tracker == null) {
                continue;
            }
            try {
                ctx.scheduler().entity(player, () -> check(player, tracker), null);
            } catch (RuntimeException e) {
                ctx.logger().warn("Scheduling an AFK check for {} failed", player.getName(), e);
            }
        }
    }

    private void check(Player player, AfkTracker tracker) {
        if (!player.isOnline() || trackers.get(player.getUniqueId()) != tracker) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!tracker.canAutoChange(now)) {
            return;
        }
        AfkConfig cfg = config.get();
        long idle = now - tracker.lastActivityAt();
        long autoMillis = cfg.autoAfkSeconds * 1000L;

        if (tracker.isAfk()) {
            if (tracker.automatic() && autoMillis > 0 && idle < autoMillis) {
                // Activity was recorded but the immediate leave did not run (for example a cancelled event).
                apply(player, tracker, false, AfkReason.ACTIVITY, "");
                return;
            }
            long actionMillis = cfg.actionsAfterMinutes * 60_000L;
            if (actionMillis > 0 && now - tracker.afkSince() >= actionMillis && tracker.claimActions()) {
                performActions(player);
            }
        } else if (autoMillis > 0 && idle >= autoMillis) {
            apply(player, tracker, true, AfkReason.AUTOMATIC, "");
        }
    }

    private void performActions(Player player) {
        AfkConfig cfg = config.get();
        if (cfg.title) {
            Component title = ctx.messages().render(player, "afk.title");
            Component subtitle = ctx.messages().render(player, "afk.subtitle",
                    Msg.number("minutes", cfg.actionsAfterMinutes));
            player.showTitle(Title.title(title, subtitle, TITLE_TIMES));
        }
        if (cfg.actionBar) {
            ctx.messages().actionBar(player, "afk.actionbar", Msg.number("minutes", cfg.actionsAfterMinutes));
        }

        List<String> commands = cfg.commands == null ? List.of() : List.copyOf(cfg.commands);
        if (commands.isEmpty()) {
            kickIfNeeded(player);
            return;
        }
        String name = player.getName();
        if (!PLAYER_NAME.matcher(name).matches()) {
            ctx.logger().warn("Refusing AFK commands for invalid player name '{}'", name);
            kickIfNeeded(player);
            return;
        }
        try {
            // Console commands can touch any region, so dispatch them from the global thread.
            ctx.scheduler().global(() -> {
                for (String raw : commands) {
                    String command = raw == null ? "" : raw.trim();
                    if (command.isEmpty()) {
                        continue;
                    }
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", name));
                    } catch (RuntimeException e) {
                        ctx.logger().warn("AFK command '{}' for {} failed", command, name, e);
                    }
                }
                kickIfNeeded(player);
            });
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling AFK commands for {} failed", name, e);
            kickIfNeeded(player);
        }
    }

    private void kickIfNeeded(Player player) {
        AfkConfig cfg = config.get();
        if (!cfg.kick || player.hasPermission(PERM_KICK_EXEMPT)) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            kick(player);
            return;
        }
        try {
            ctx.scheduler().entity(player, () -> kick(player), null);
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling the AFK kick of {} failed", player.getName(), e);
        }
    }

    private void kick(Player player) {
        AfkConfig cfg = config.get();
        if (!player.isOnline() || !cfg.kick || player.hasPermission(PERM_KICK_EXEMPT)) {
            return;
        }
        player.kick(ctx.messages().render(player, "afk.kicked", Msg.number("minutes", cfg.actionsAfterMinutes)));
    }

    // ---- activity ----

    private void activity(Player player) {
        AfkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            return;
        }
        tracker.recordActivity(System.currentTimeMillis());
        if (tracker.isAfk()) {
            leaveIfAfk(player, AfkReason.ACTIVITY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        Player player = event.getPlayer();
        AfkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            return;
        }
        var input = player.getCurrentInput();
        boolean activeInput = input.isForward() || input.isBackward() || input.isLeft() || input.isRight()
                || input.isJump();
        MovementFlags flags = new MovementFlags(player.isInsideVehicle(), player.isInWater(), player.isInLava(),
                activeInput);
        if (tracker.recordMovement(AfkPosition.of(to), flags, System.currentTimeMillis(), rules)
                == AfkTracker.ActivityResult.ACTIVE && tracker.isAfk()) {
            leaveIfAfk(player, AfkReason.ACTIVITY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        AfkTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            return;
        }
        tracker.recordActivity(System.currentTimeMillis());
        if (tracker.isAfk()) {
            // Chat is asynchronous; the state change must happen on the player's own thread.
            try {
                ctx.scheduler().entity(player, () -> leaveIfAfk(player, AfkReason.ACTIVITY), null);
            } catch (RuntimeException e) {
                ctx.logger().warn("Scheduling an AFK leave for {} failed", player.getName(), e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (command.equals("/afk") || command.startsWith("/afk ")
                || command.equals("/atrium:afk") || command.startsWith("/atrium:afk ")) {
            return; // /afk must be able to toggle even while AFK
        }
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            activity(player);
        }
    }

    // ---- lifecycle ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        register(event.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        AfkTracker tracker = trackers.remove(player.getUniqueId());
        if (tracker == null || !tracker.isAfk()) {
            return;
        }
        AfkTracker.Exit exit = tracker.endAfk(System.currentTimeMillis());
        restore(player, exit);
        recordAfkSession(player.getUniqueId(), exit.durationMillis());
    }

    // ---- configuration ----

    private static void validate(AfkConfig cfg) {
        if (cfg.autoAfkSeconds < 0 || cfg.autoAfkSeconds > 86_400) {
            throw new IllegalArgumentException("afk.autoAfkSeconds must be 0-86400");
        }
        if (cfg.checkIntervalSeconds < 1 || cfg.checkIntervalSeconds > 60) {
            throw new IllegalArgumentException("afk.checkIntervalSeconds must be 1-60");
        }
        if (!(cfg.movementThreshold > 0) || cfg.movementThreshold > 10) {
            throw new IllegalArgumentException("afk.movementThreshold must be greater than 0 and at most 10");
        }
        if (!(cfg.rotationThresholdDegrees > 0) || cfg.rotationThresholdDegrees > 360) {
            throw new IllegalArgumentException("afk.rotationThresholdDegrees must be greater than 0 and at most 360");
        }
        if (cfg.maxMovementPerCheck < cfg.movementThreshold || cfg.maxMovementPerCheck > 64) {
            throw new IllegalArgumentException("afk.maxMovementPerCheck must be at least movementThreshold and at most 64");
        }
        if (cfg.antiAfkPoolEnabled
                && (cfg.antiAfkPoolBoxSize <= 0 || cfg.antiAfkPoolBoxSize > 64
                        || cfg.antiAfkPoolWindowSeconds < 1 || cfg.antiAfkPoolWindowSeconds > 86_400)) {
            throw new IllegalArgumentException("afk anti-AFK-pool box must be 0-64 and window 1-86400 seconds");
        }
        if (cfg.actionsAfterMinutes < 0 || cfg.actionsAfterMinutes > 1_440) {
            throw new IllegalArgumentException("afk.actionsAfterMinutes must be 0-1440");
        }
        if (cfg.kick && cfg.actionsAfterMinutes == 0) {
            throw new IllegalArgumentException("afk.kick requires actionsAfterMinutes to be greater than 0");
        }
        if (cfg.maxMessageLength < 1 || cfg.maxMessageLength > 1_024) {
            throw new IllegalArgumentException("afk.maxMessageLength must be 1-1024");
        }
        if (cfg.commands == null) {
            cfg.commands = new ArrayList<>();
        }
        if (cfg.commands.size() > 64) {
            throw new IllegalArgumentException("afk.commands may contain at most 64 entries");
        }
        for (String command : cfg.commands) {
            if (command != null && command.length() > 512) {
                throw new IllegalArgumentException("afk.commands entries must be at most 512 characters");
            }
        }
    }
}
