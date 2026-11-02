package dev.yanianz.atrium.module.bossbar;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.module.display.DisplayPlaceholders;
import dev.yanianz.atrium.module.display.TemplateRenderer;
import dev.yanianz.atrium.module.display.ToggleCommand;
import dev.yanianz.atrium.module.display.ToggleStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Boss bar announcements and {@code /bossbarmsg}.
 *
 * <p>One global timer decides which announcement is visible ({@link Rotation}); every player's bars are
 * created, updated and removed on that player's own thread. {@code /bossbarmsg} only shows text: there is no
 * way to attach commands that run when a bar ends (such a flag must never run console commands).
 */
public final class BossbarModule implements AtriumModule, Listener {
    static final String PERM_TOGGLE = "atrium.bossbar.toggle";
    static final String PERM_MESSAGE = "atrium.bossbarmsg";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<BossbarConfig> config;
    private ToggleStore store;
    private TemplateRenderer renderer;
    private volatile DisplayPlaceholders placeholders;
    private volatile BossbarSettings settings;
    private volatile TaskHandle timer = TaskHandle.NOOP;
    private volatile long clock;
    private final Map<UUID, PlayerBars> bars = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "bossbar";
    }

    @Override
    public String description() {
        return "Boss bar announcements and admin boss bar messages";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(BossbarConfig.class, BossbarConfig::new);
        ctx.migrate(ToggleStore.migrations(id()));
        this.store = new ToggleStore(ctx.database(), id());
        ctx.component(store.component);
        this.renderer = new TemplateRenderer(ctx.messages(), ctx.logger());
        applyConfig();
        ctx.permission(PERM_TOGGLE, PermissionDefault.TRUE, "Hide or show boss bar announcements for yourself");
        ctx.permission(PERM_MESSAGE, PermissionDefault.OP, "Show a timed boss bar message to players");
        ctx.listen(this);

        ToggleCommand toggle = new ToggleCommand(ctx, store, this::applyToggle);
        // Own name: replacing vanilla /bossbar would break its tab completion on Paper.
        ctx.command(CommandSpec.of(Cmds.literal("bossbars", PERM_TOGGLE)
                .executes(toggle::execute)
                .then(Cmds.literal("toggle", PERM_TOGGLE).executes(toggle::execute))
                .build(), "Toggle boss bar announcements"));
        ctx.command(CommandSpec.of(Cmds.literal("bossbarmsg", PERM_MESSAGE)
                .executes(this::usage)
                .then(Cmds.literal("all").executes(this::usage).then(secondsAndMessage(true)))
                .then(Commands.argument("targets", ArgumentTypes.players()).then(secondsAndMessage(false)))
                .build(), "Show a timed boss bar message"));
        startTimer();
    }

    @Override
    public void reload() {
        applyConfig();
        startTimer();
    }

    @Override
    public void disable() {
        timer.cancel();
        if (ctx.plugin().isEnabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerBars playerBars = bars.get(player.getUniqueId());
                if (playerBars != null) {
                    ctx.scheduler().entity(player, () -> playerBars.hideAll(player), null);
                }
            }
        }
        bars.clear();
        DisplayPlaceholders current = placeholders;
        if (current != null) {
            current.clear();
        }
    }

    private void applyConfig() {
        BossbarConfig raw = config.get();
        BiConsumer<String, String> warn = (option, problem) -> ctx.logger().warn("bossbar.yml {}: {}", option, problem);
        BossbarSettings next = BossbarSettings.from(raw, warn);
        int refresh = BossbarSettings.clamp(raw.asyncRefreshSeconds, 5, 3600, "async-refresh-seconds", warn);
        DisplayPlaceholders previous = placeholders;
        DisplayPlaceholders fresh = new DisplayPlaceholders(ctx.services(), ctx.logger(), Duration.ofSeconds(refresh));
        fresh.missingText(raw.missingValue);
        renderer.placeholderApi(raw.placeholderApi);
        this.placeholders = fresh;
        this.settings = next;
        if (previous != null) {
            previous.clear();
        }
    }

    private void startTimer() {
        timer.cancel();
        long period = settings.intervalTicks();
        timer = ctx.track(ctx.scheduler().globalTimer(() -> cycle(period), 1L, period));
    }

    /** Global thread. */
    private void cycle(long period) {
        long tick = clock += period;
        BossbarSettings current = settings;
        Rotation.Active active = current.announcementsEnabled() ? current.rotation().at(tick) : null;
        Map<String, String> frames = current.animations().framesAt(tick);
        DisplayPlaceholders.ServerView view = placeholders.serverView();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (active == null && !bars.containsKey(player.getUniqueId())) {
                continue; // nothing to show or expire for this player
            }
            ctx.scheduler().entity(player, () -> update(player, current, active, frames, view), null);
        }
    }

    /** Player's thread. */
    private void update(Player player, BossbarSettings s, Rotation.Active active, Map<String, String> frames,
            DisplayPlaceholders.ServerView view) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        PlayerBars playerBars = bars.get(id);
        Optional<AtriumUser> user = ctx.users().cached(id);
        boolean hidden = user.isEmpty() || user.get().get(store.component).hidden();
        BossbarSettings.AnnouncementSpec spec = active == null || hidden || active.index() >= s.announcements().size()
                ? null : s.announcements().get(active.index());
        if (spec != null && !spec.targeting().matches(player.getWorld().getName(), player::hasPermission)) {
            spec = null;
        }
        if (spec == null) {
            if (playerBars != null) {
                playerBars.hideAnnouncement(player);
            }
        } else {
            if (playerBars == null) {
                playerBars = bars.computeIfAbsent(id, k -> new PlayerBars());
            }
            TagResolver values = placeholders.values(player, view).resolver();
            Component title = renderer.render(player, spec.text(), frames, values);
            float progress = Rotation.progress(spec.countdown(), spec.progress(), active.elapsed());
            BossBar bar = playerBars.announcement;
            if (bar == null || playerBars.announcementSlot != active.slot()) {
                playerBars.hideAnnouncement(player);
                bar = BossBar.bossBar(title, progress, spec.color(), spec.overlay());
                playerBars.announcement = bar;
                playerBars.announcementSlot = active.slot();
                player.showBossBar(bar);
            } else {
                if (!title.equals(bar.name())) {
                    bar.name(title);
                }
                if (Math.abs(bar.progress() - progress) > 0.001f) {
                    bar.progress(progress);
                }
            }
        }
        if (playerBars != null) {
            playerBars.tickMessages(player, System.nanoTime());
            if (playerBars.isEmpty()) {
                bars.remove(id, playerBars);
            }
        }
    }

    // ---- /bossbarmsg ----

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "bossbar.message-usage");
        return Cmds.OK;
    }

    private RequiredArgumentBuilder<CommandSourceStack, Integer> secondsAndMessage(boolean all) {
        return Commands.argument("seconds", IntegerArgumentType.integer(1, 86_400))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> message(c, all)));
    }

    private int message(CommandContext<CommandSourceStack> c, boolean all) throws CommandSyntaxException {
        BossbarSettings s = settings;
        int seconds = IntegerArgumentType.getInteger(c, "seconds");
        if (seconds > s.maxMessageSeconds()) {
            throw cmds.fail(c.getSource(), "bossbar.too-long", Msg.number("max", s.maxMessageSeconds()));
        }
        // The text is written by a sender holding the admin-only atrium.bossbarmsg permission, so MiniMessage
        // is allowed. It is never fed to PlaceholderAPI and never runs commands.
        String message = StringArgumentType.getString(c, "message").strip();
        if (message.isEmpty()) {
            throw cmds.fail(c.getSource(), "bossbar.empty-message");
        }
        if (message.length() > s.maxMessageLength()) {
            throw cmds.fail(c.getSource(), "bossbar.message-too-long", Msg.number("max", s.maxMessageLength()));
        }
        List<Player> targets = all ? List.copyOf(Bukkit.getOnlinePlayers()) : cmds.argPlayers(c, "targets");
        DisplayPlaceholders.ServerView view = placeholders.serverView();
        long duration = TimeUnit.SECONDS.toNanos(seconds);
        for (Player target : targets) {
            ctx.scheduler().entity(target, () -> showMessage(target, s, message, duration, view), null);
        }
        reply(c.getSource().getSender(), "bossbar.message-sent", Msg.number("count", targets.size()),
                Msg.number("seconds", seconds));
        return Cmds.OK;
    }

    /** Target's thread. */
    private void showMessage(Player target, BossbarSettings s, String message, long durationNanos,
            DisplayPlaceholders.ServerView view) {
        if (!target.isOnline()) {
            return;
        }
        Component text = ctx.messages().parseTrusted(message, placeholders.values(target, view).resolver());
        BossBar bar = BossBar.bossBar(text, 1f, s.messageColor(), s.messageOverlay());
        bars.computeIfAbsent(target.getUniqueId(), k -> new PlayerBars())
                .addMessage(target, new PlayerBars.TimedBar(bar, System.nanoTime(), durationNanos), s.maxMessageBars());
    }

    /** Player's thread, right after /bossbar toggle. Announcements reappear with the next rotation update. */
    private void applyToggle(Player player, boolean hidden) {
        if (hidden) {
            PlayerBars playerBars = bars.get(player.getUniqueId());
            if (playerBars != null) {
                playerBars.hideAnnouncement(player);
            }
        }
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        bars.remove(id);
        placeholders.forget(id);
    }
}
