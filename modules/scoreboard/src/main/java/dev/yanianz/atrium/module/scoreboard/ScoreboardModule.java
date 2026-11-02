package dev.yanianz.atrium.module.scoreboard;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.module.display.DisplayPlaceholders;
import dev.yanianz.atrium.module.display.TemplateRenderer;
import dev.yanianz.atrium.module.display.ToggleCommand;
import dev.yanianz.atrium.module.display.ToggleStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Per-player sidebar. Rendering is delegated to a {@link SidebarBackend}: the Bukkit scoreboard API on
 * Paper, or PacketEvents on Folia where no scoreboard API exists. A player whose scoreboard was replaced
 * by another plugin is left alone. {@code /sidebar toggle} is persisted per player.
 */
public final class ScoreboardModule implements AtriumModule, Listener {
    static final String PERM_TOGGLE = "atrium.scoreboard.toggle";

    private ModuleContext ctx;
    private ConfigHandle<ScoreboardConfig> config;
    private ToggleStore store;
    private TemplateRenderer renderer;
    private volatile DisplayPlaceholders placeholders;
    private volatile ScoreboardSettings settings;
    private volatile TaskHandle timer = TaskHandle.NOOP;
    private volatile long clock;
    private volatile SidebarBackend backend = SidebarBackend.NONE;
    /** Backend mode the log line was last written for; avoids repeating it on every reload. */
    private volatile BackendMode appliedBackendMode;
    private final Set<UUID> foreignReported = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "scoreboard";
    }

    @Override
    public String description() {
        return "Per-player sidebar scoreboards";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(ScoreboardConfig.class, ScoreboardConfig::new);
        ctx.migrate(ToggleStore.migrations(id()));
        this.store = new ToggleStore(ctx.database(), id());
        ctx.component(store.component);
        this.renderer = new TemplateRenderer(ctx.messages(), ctx.logger());
        applyConfig();
        configureBackend();
        ctx.permission(PERM_TOGGLE, PermissionDefault.TRUE, "Hide or show your own sidebar");
        ctx.listen(this);
        ToggleCommand toggle = new ToggleCommand(ctx, store, this::applyToggle);
        // Own name: replacing vanilla /scoreboard would break its tab completion on Paper.
        LiteralArgumentBuilder<CommandSourceStack> root = Cmds.literal("sidebar", PERM_TOGGLE)
                .executes(toggle::execute)
                .then(Cmds.literal("toggle", PERM_TOGGLE).executes(toggle::execute));
        ctx.command(CommandSpec.of(root.build(), "Toggle your sidebar"));
        startTimer();
    }

    @Override
    public void reload() {
        timer.cancel();
        applyConfig();
        configureBackend();
        startTimer();
    }

    @Override
    public void disable() {
        timer.cancel();
        SidebarBackend current = backend;
        backend = SidebarBackend.NONE;
        // Always hand the sidebar back, even while the plugin is being disabled or reloaded: the client
        // keeps a packet objective until it receives the removal. hide() itself skips offline players.
        if (current.kind() != SidebarBackend.Kind.NONE) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ctx.scheduler().entity(player, () -> current.hide(player), null);
            }
        }
        foreignReported.clear();
        DisplayPlaceholders currentPlaceholders = placeholders;
        if (currentPlaceholders != null) {
            currentPlaceholders.clear();
        }
    }

    private void applyConfig() {
        ScoreboardConfig raw = config.get();
        BiConsumer<String, String> warn = (option, problem) -> ctx.logger().warn("scoreboard.yml {}: {}", option, problem);
        ScoreboardSettings next = ScoreboardSettings.from(raw, warn);
        int refresh = ScoreboardSettings.clamp(raw.asyncRefreshSeconds, 5, 3600, "async-refresh-seconds", warn);
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

    /**
     * Picks and, when it changed, swaps the rendering backend. A swap hides the old backend's sidebars on
     * every player's thread before the new one takes over, so no two boards fight over the sidebar slot.
     */
    private void configureBackend() {
        ScoreboardSettings current = settings;
        BackendMode mode = current.backend();
        boolean folia = ctx.scheduler().isFolia();
        // Only load PacketEvents classes when they can actually be used; auto never needs them on Paper.
        boolean wantPackets = mode == BackendMode.PACKETS || (mode == BackendMode.AUTO && folia);
        SidebarBackend candidate = wantPackets ? PacketBackendFactory.create(ctx.logger(), folia) : null;
        SidebarBackend.Kind kind = BackendSelection.select(mode, !folia, candidate != null);
        SidebarBackend active = backend;
        boolean modeChanged = appliedBackendMode != mode;
        appliedBackendMode = mode;
        if (active.kind() == kind) {
            if (kind == SidebarBackend.Kind.NONE && modeChanged) {
                logUnavailable(mode, folia);
            }
            return;
        }
        switch (kind) {
            case BUKKIT -> swap(new BukkitSidebarBackend(ctx.logger()));
            case PACKETS -> swap(candidate);
            case NONE -> swap(SidebarBackend.NONE);
        }
        switch (kind) {
            case BUKKIT -> ctx.logger().info("Sidebar backend: the Bukkit scoreboard API.");
            case PACKETS -> ctx.logger().info("Sidebar backend: PacketEvents (Folia-compatible packets).");
            case NONE -> logUnavailable(mode, folia);
        }
    }

    private void swap(SidebarBackend next) {
        SidebarBackend old = backend;
        // Block updates already queued for the old backend: render() ignores them once the field changed.
        backend = SidebarBackend.NONE;
        if (old.kind() != SidebarBackend.Kind.NONE) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ctx.scheduler().entity(player, () -> old.hide(player), null);
            }
        }
        backend = next;
    }

    private void logUnavailable(BackendMode mode, boolean folia) {
        if (folia && mode == BackendMode.AUTO) {
            ctx.logger().warn("Folia does not support the scoreboard API and PacketEvents is not installed; "
                    + "sidebars stay hidden. /sidebar still stores each player's preference. Install the "
                    + "packetevents plugin to enable sidebars on Folia.");
            return;
        }
        String why = switch (mode) {
            case AUTO -> "no scoreboard API is available on this server";
            case BUKKIT -> "backend 'bukkit' needs the Bukkit scoreboard API, which Folia does not have";
            case PACKETS -> "backend 'packets' needs the packetevents plugin (1.20.3 or newer)";
        };
        ctx.logger().warn("Sidebars stay hidden: {}; /sidebar still stores each player's preference.", why);
    }

    private void startTimer() {
        timer.cancel();
        long period = settings.intervalTicks();
        timer = ctx.track(ctx.scheduler().globalTimer(() -> cycle(period), 1L, period));
    }

    /** Global thread. */
    private void cycle(long period) {
        long tick = clock += period;
        SidebarBackend current = backend;
        if (current.kind() == SidebarBackend.Kind.NONE) {
            return;
        }
        ScoreboardSettings currentSettings = settings;
        Map<String, String> frames = currentSettings.animations().framesAt(tick);
        DisplayPlaceholders.ServerView view = placeholders.serverView();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player,
                    () -> render(player, current, currentSettings, frames, view), null);
        }
    }

    /** Player's thread. */
    private void render(Player player, SidebarBackend sidebarBackend, ScoreboardSettings s,
            Map<String, String> frames, DisplayPlaceholders.ServerView view) {
        if (!player.isOnline() || sidebarBackend != backend) {
            return;
        }
        UUID id = player.getUniqueId();
        Optional<AtriumUser> user = ctx.users().cached(id);
        if (user.isEmpty()) {
            return; // still loading: do not flash a sidebar the player may have hidden
        }
        boolean hidden = user.get().get(store.component).hidden();
        ScoreboardSettings.BoardSpec spec = hidden ? null : s.boardFor(player.getWorld().getName(), player::hasPermission);
        if (spec == null) {
            sidebarBackend.hide(player);
            return;
        }
        if (!sidebarBackend.canRender(player)) {
            // Another plugin gave this player its own scoreboard: never take it over.
            sidebarBackend.hide(player);
            if (foreignReported.add(id)) {
                ctx.logger().info("{} uses a scoreboard of another plugin; the Atrium sidebar is not shown to them",
                        player.getName());
            }
            return;
        }
        TagResolver values = placeholders.values(player, view).resolver();
        Component title = renderer.render(player, spec.title(), frames, values);
        List<Component> lines = new ArrayList<>(spec.lines().size());
        for (String line : spec.lines()) {
            lines.add(renderer.render(player, line, frames, values));
        }
        sidebarBackend.update(player, title, lines);
    }

    /** Player's thread, right after /sidebar toggle. */
    private void applyToggle(Player player, boolean hidden) {
        SidebarBackend current = backend;
        if (current.kind() == SidebarBackend.Kind.NONE) {
            ctx.messages().send(player, "scoreboard.unsupported");
            return;
        }
        if (hidden) {
            current.hide(player);
        } else {
            ScoreboardSettings currentSettings = settings;
            ctx.scheduler().supplyGlobal(placeholders::serverView).thenAccept(view -> ctx.scheduler().entity(player,
                    () -> render(player, current, currentSettings, currentSettings.animations().framesAt(clock), view),
                    null)).exceptionally(error -> {
                        ctx.logger().warn("Showing the sidebar of {} failed", player.getName(), error);
                        return null;
                    });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        backend.forget(event.getPlayer());
        foreignReported.remove(id);
        placeholders.forget(id);
    }
}
