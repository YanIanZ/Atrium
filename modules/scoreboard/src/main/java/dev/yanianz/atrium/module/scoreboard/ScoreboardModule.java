package dev.yanianz.atrium.module.scoreboard;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.module.display.DisplayPlaceholders;
import dev.yanianz.atrium.module.display.TabResync;
import dev.yanianz.atrium.module.display.TemplateRenderer;
import dev.yanianz.atrium.module.display.ToggleCommand;
import dev.yanianz.atrium.module.display.ToggleStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Per-player sidebar. Rendering is delegated to a {@link SidebarBackend}: the TAB plugin's scoreboard API when
 * TAB runs its scoreboard feature, the Bukkit scoreboard API on Paper, or PacketEvents on Folia where no
 * scoreboard API exists. A player whose scoreboard was replaced by another plugin is left alone.
 * {@code /sidebar toggle} is persisted per player. Backend switches run on the global thread.
 *
 * <p>With TAB, Atrium's sidebar is the only one shown while Atrium shows one, and {@code tab-default-scoreboard}
 * decides whether TAB's own default scoreboards appear while it shows none ({@link TabSidebarSync}). TAB's load
 * and reload events re-apply Atrium's sidebars right away ({@link TabResync}).
 */
public final class ScoreboardModule implements AtriumModule, Listener {
    static final String PERM_TOGGLE = "atrium.scoreboard.toggle";
    static final String PERM_ENGINE = "atrium.scoreboard.engine";

    private ModuleContext ctx;
    private ConfigHandle<ScoreboardConfig> config;
    private ToggleStore store;
    private TemplateRenderer renderer;
    private volatile DisplayPlaceholders placeholders;
    private volatile ScoreboardSettings settings;
    private volatile TaskHandle timer = TaskHandle.NOOP;
    private volatile long clock;
    private volatile boolean enabled;
    /** Backend + mode of the last "two sidebar sources" warning; avoids repeating it. */
    private volatile String conflictLogged = "";
    private volatile SidebarBackend backend = SidebarBackend.NONE;
    /** Backend mode the log line was last written for; avoids repeating it on every reload. */
    private volatile BackendMode appliedBackendMode;
    /** Why the TAB backend is not used although it was configured or auto found TAB; empty otherwise. */
    private volatile String fallbackReason = "";
    /** Auto left TAB because TAB was disabled at runtime; it returns when TAB is enabled again. */
    private volatile boolean leftTabOnDisable;
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
        enabled = true;
        configureBackend();
        ctx.permission(PERM_TOGGLE, PermissionDefault.TRUE, "Hide or show your own sidebar");
        ctx.permission(PERM_ENGINE, PermissionDefault.OP,
                "Show the sidebar engine and who owns the sidebar slot (/sidebar engine)");
        ctx.listen(this);
        ctx.listen(new PluginWatcher());
        ToggleCommand toggle = new ToggleCommand(ctx, store, this::applyToggle);
        // Own name: replacing vanilla /scoreboard would break its tab completion on Paper.
        LiteralArgumentBuilder<CommandSourceStack> root = Cmds.literal("sidebar", PERM_TOGGLE)
                .executes(toggle::execute)
                .then(Cmds.literal("toggle", PERM_TOGGLE).executes(toggle::execute))
                .then(Cmds.literal("engine", PERM_ENGINE).executes(this::engineInfo));
        ctx.command(CommandSpec.of(root.build(), "Toggle your sidebar"));
        startTimer();
    }

    @Override
    public void reload() {
        timer.cancel();
        applyConfig();
        // Reload runs on the sender's thread; backend switches can call into TAB and belong to the global thread.
        ctx.track(ctx.scheduler().global(() -> {
            configureBackend();
            startTimer();
        }));
    }

    @Override
    public void disable() {
        enabled = false;
        timer.cancel();
        SidebarBackend current = backend;
        backend = SidebarBackend.NONE;
        // Always hand the sidebar back, even while the plugin is being disabled or reloaded: the client
        // keeps a packet objective until it receives the removal. hide() itself skips offline players.
        if (current.kind() == SidebarBackend.Kind.TAB) {
            current.stop();
        } else if (current.kind() != SidebarBackend.Kind.NONE) {
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
     * every player's thread (or, for TAB, removes Atrium's TAB scoreboards) before the new one takes over, so no
     * two boards fight over the sidebar slot. Global thread, or enable.
     */
    private void configureBackend() {
        ScoreboardSettings current = settings;
        BackendMode mode = current.backend();
        boolean folia = ctx.scheduler().isFolia();
        SidebarBackend active = backend;
        // TAB first (auto and tab); its classes are only touched when the TAB plugin is enabled.
        SidebarBackend tabCandidate = null;
        boolean tabPlugin = false;
        String tabProblem = "";
        if (mode == BackendMode.TAB || mode == BackendMode.AUTO) {
            tabPlugin = TabBackendFactory.pluginEnabled();
            boolean feature = tabPlugin && TabBackendFactory.scoreboardEnabled(ctx.logger());
            if (feature) {
                if (active.kind() == SidebarBackend.Kind.TAB && active.usable()) {
                    tabCandidate = active;
                } else {
                    TabResync resync = new TabResync(task -> ctx.track(ctx.scheduler().global(task)),
                            this::onTabResync);
                    tabCandidate = TabBackendFactory.create(ctx.logger(), resync, () -> settings.tabDefault());
                    if (tabCandidate == null) {
                        resync.close();
                    }
                }
            }
            tabProblem = BackendSelection.tabProblem(tabPlugin, feature, tabCandidate != null);
        }
        // Only load PacketEvents classes when they can actually be used; auto never needs them on Paper.
        boolean wantPackets = tabCandidate == null
                && (mode == BackendMode.PACKETS || (mode != BackendMode.BUKKIT && folia));
        SidebarBackend candidate = wantPackets ? PacketBackendFactory.create(ctx.logger(), folia) : null;
        SidebarBackend.Kind kind = BackendSelection.select(mode, !folia, candidate != null, tabCandidate != null);
        // An explicit tab always explains its fallback; auto only when TAB is installed but cannot be used.
        String reason = kind != SidebarBackend.Kind.TAB && (mode == BackendMode.TAB || (mode == BackendMode.AUTO && tabPlugin))
                ? tabProblem
                : "";
        boolean changed = appliedBackendMode != mode || !reason.equals(fallbackReason);
        appliedBackendMode = mode;
        fallbackReason = reason;
        warnConflict(kind, tabPlugin || TabBackendFactory.pluginEnabled());
        if (active.kind() == kind && (kind != SidebarBackend.Kind.TAB || active == tabCandidate)) {
            if (changed) {
                logFallback(reason, kind);
                if (kind == SidebarBackend.Kind.NONE) {
                    logUnavailable(mode, folia);
                }
            }
            return;
        }
        switch (kind) {
            case BUKKIT -> swap(new BukkitSidebarBackend(ctx.logger()));
            case PACKETS -> swap(candidate);
            case TAB -> swap(tabCandidate);
            case NONE -> swap(SidebarBackend.NONE);
        }
        logFallback(reason, kind);
        switch (kind) {
            case BUKKIT -> ctx.logger().info("Sidebar backend: the Bukkit scoreboard API.");
            case PACKETS -> ctx.logger().info("Sidebar backend: PacketEvents (Folia-compatible packets).");
            case TAB -> ctx.logger().info("Sidebar backend: the TAB plugin's scoreboard API.");
            case NONE -> logUnavailable(mode, folia);
        }
    }

    /** Atrium draws a sidebar itself while TAB runs its scoreboard feature: both fight over the sidebar slot. */
    private void warnConflict(SidebarBackend.Kind kind, boolean tabPlugin) {
        boolean tabScoreboard = tabPlugin && TabBackendFactory.scoreboardEnabled(ctx.logger());
        TabSidebarSync.Defaults defaults = TabSidebarSync.defaults(kind, tabScoreboard, settings.tabDefault());
        String state = kind.id() + "|" + defaults;
        if (state.equals(conflictLogged)) {
            return;
        }
        conflictLogged = state;
        if (defaults == TabSidebarSync.Defaults.CONFLICT) {
            ctx.logger().warn("TAB runs its scoreboard feature while Atrium draws sidebars with the {} backend: two "
                    + "sidebars fight over the slot. Set backend: auto or tab in scoreboard.yml, or disable TAB's "
                    + "scoreboard feature.", kind.id());
        }
    }

    /**
     * Global thread: TAB loaded players or reloaded (Atrium's sidebars there are gone), or new sidebars are
     * waiting. Hands them to TAB right away, then re-renders the reloaded players so new joiners are covered.
     */
    private void onTabResync(TabResync.Batch batch) {
        SidebarBackend current = backend;
        if (current.kind() != SidebarBackend.Kind.TAB || !enabled) {
            return;
        }
        if (!current.usable()) {
            configureBackend();
            return;
        }
        current.invalidate(batch);
        current.flush();
        if (batch.onlyWake()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (batch.affects(player.getUniqueId())) {
                renderSoon(player);
            }
        }
    }

    /** Any thread: renders one player soon instead of on the next cycle. */
    private void renderSoon(Player player) {
        ScoreboardSettings currentSettings = settings;
        ctx.scheduler().supplyGlobal(placeholders::serverView).thenAccept(view -> {
            SidebarBackend current = backend;
            if (current.kind() == SidebarBackend.Kind.NONE) {
                return;
            }
            ctx.scheduler().entity(player, () -> render(player, current, currentSettings,
                    currentSettings.animations().framesAt(clock), view), null);
        }).exceptionally(error -> {
            ctx.logger().warn("Rendering the sidebar of {} failed", player.getName(), error);
            return null;
        });
    }

    private void logFallback(String reason, SidebarBackend.Kind kind) {
        if (reason.isEmpty()) {
            return;
        }
        String why = switch (reason) {
            case BackendSelection.TAB_PLUGIN_MISSING -> "the TAB plugin is not enabled";
            case BackendSelection.TAB_SCOREBOARD_DISABLED -> "TAB's scoreboard feature is disabled in TAB's config";
            default -> "TAB's scoreboard API could not be used";
        };
        ctx.logger().warn("Sidebar backend 'tab' is not used: {}; using {}", why, kind.id());
    }

    private void swap(SidebarBackend next) {
        SidebarBackend old = backend;
        // Block updates already queued for the old backend: render() ignores them once the field changed.
        backend = SidebarBackend.NONE;
        if (old.kind() == SidebarBackend.Kind.TAB) {
            // TAB keeps its scoreboards until Atrium removes them; that happens here, on the global thread.
            old.stop();
        } else if (old.kind() != SidebarBackend.Kind.NONE) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ctx.scheduler().entity(player, () -> old.hide(player), null);
            }
        }
        backend = next;
    }

    /** Leaves TAB when it is disabled, and returns when it comes back. */
    final class PluginWatcher implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onDisable(PluginDisableEvent event) {
            if (Bukkit.isStopping() || !ctx.plugin().isEnabled()
                    || !TabBackendFactory.PLUGIN.equals(event.getPlugin().getName())
                    || backend.kind() != SidebarBackend.Kind.TAB) {
                return;
            }
            SidebarBackend old = backend;
            backend = SidebarBackend.NONE;
            // Never call into a plugin that is being disabled; its scoreboards go away with it.
            old.detach();
            leftTabOnDisable = true;
            fallbackReason = BackendSelection.TAB_PLUGIN_DISABLED;
            ctx.logger().warn("TAB was disabled; sidebars switch to another backend");
            // Next global tick: TAB counts as disabled by then.
            ctx.track(ctx.scheduler().global(ScoreboardModule.this::configureBackend));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnable(PluginEnableEvent event) {
            BackendMode mode = settings.backend();
            if (Bukkit.isStopping() || !ctx.plugin().isEnabled()
                    || !TabBackendFactory.PLUGIN.equals(event.getPlugin().getName())
                    || backend.kind() == SidebarBackend.Kind.TAB
                    || !(mode == BackendMode.TAB || (mode == BackendMode.AUTO && leftTabOnDisable))) {
                return;
            }
            leftTabOnDisable = false;
            ctx.logger().info("TAB was enabled; sidebars move back to TAB");
            ctx.track(ctx.scheduler().global(ScoreboardModule.this::configureBackend));
        }
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
            case TAB -> "backend 'tab' needs the TAB plugin with its scoreboard feature enabled, and no other "
                    + "backend can take over here";
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
        if (!backend.usable()) {
            // TAB was reloaded with its scoreboard feature off, or its API went away: pick another backend.
            configureBackend();
        }
        SidebarBackend current = backend;
        if (current.kind() == SidebarBackend.Kind.NONE) {
            return;
        }
        // Applies what the player threads recorded in the previous cycle (TAB backend only).
        current.flush();
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
        if (!player.isOnline() || sidebarBackend != backend || !enabled) {
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
            renderSoon(player);
        }
    }

    private int engineInfo(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        // The TAB backend is only called from the global thread.
        ctx.track(ctx.scheduler().global(() -> sendEngineInfo(sender)));
        return Cmds.OK;
    }

    /** Global thread. */
    private void sendEngineInfo(CommandSender sender) {
        SidebarBackend active = backend;
        ScoreboardSettings current = settings;
        boolean folia = ctx.scheduler().isFolia();
        boolean tabScoreboard = TabBackendFactory.scoreboardEnabled(ctx.logger());
        SidebarCapabilities capabilities = SidebarCapabilities.of(active.kind(), folia);
        reply(sender, "scoreboard.engine-header", Msg.unparsed("engine", active.kind().id()),
                Msg.unparsed("configured", current.backend().name().toLowerCase(Locale.ROOT)));
        reply(sender, "scoreboard.engine-owner-sidebar",
                Msg.component("owner", ctx.messages().render(sender, "scoreboard.owner-" + active.kind().id())));
        TabSidebarSync.Defaults defaults = TabSidebarSync.defaults(active.kind(), tabScoreboard, current.tabDefault());
        reply(sender, "scoreboard.engine-owner-tab-defaults",
                Msg.component("owner", ctx.messages().render(sender, "scoreboard.defaults-" + defaults.key)));
        SidebarBackend.TabStatus status = active.tabStatus();
        if (status != null) {
            reply(sender, "scoreboard.engine-applied", Msg.number("applied", status.appliedPlayers()),
                    Msg.number("loaded", status.loadedPlayers()), Msg.number("hidden", status.hiddenDefaults()),
                    Msg.number("reapplied", status.reapplied()));
        }
        reply(sender, "scoreboard.engine-capabilities",
                Msg.unparsed("supported", SidebarCapabilities.describe(capabilities.supported())));
        if (capabilities.missing().isEmpty()) {
            reply(sender, "scoreboard.engine-all-capabilities");
        } else {
            reply(sender, "scoreboard.engine-capabilities-missing",
                    Msg.unparsed("missing", SidebarCapabilities.describe(capabilities.missing())));
        }
        if (active.kind() == SidebarBackend.Kind.TAB) {
            reply(sender, "scoreboard.engine-tab-sidebar-note");
        }
        List<String> available = new ArrayList<>();
        if (!folia) {
            available.add(SidebarBackend.Kind.BUKKIT.id());
        }
        if (Bukkit.getPluginManager().isPluginEnabled(PacketBackendFactory.PLUGIN)) {
            available.add(SidebarBackend.Kind.PACKETS.id());
        }
        if (tabScoreboard) {
            available.add(SidebarBackend.Kind.TAB.id());
        }
        reply(sender, "scoreboard.engine-available",
                Msg.unparsed("engines", available.isEmpty() ? "-" : String.join(", ", available)));
        String reason = fallbackReason;
        if (!reason.isEmpty()) {
            reply(sender, "scoreboard.engine-fallback-note",
                    Msg.component("reason", ctx.messages().render(sender, "scoreboard.engine-reason-" + reason)));
        } else if (active.kind() == SidebarBackend.Kind.NONE) {
            reply(sender, "scoreboard.engine-fallback-note",
                    Msg.component("reason", ctx.messages().render(sender, "scoreboard.engine-reason-none")));
        }
    }

    /** Messages to a player go out on the player's own thread. */
    private void reply(CommandSender sender, String key, TagResolver... resolvers) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, resolvers), null);
        } else {
            ctx.messages().send(sender, key, resolvers);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Render right away; the TAB backend hands it to TAB as soon as TAB has loaded the player.
        renderSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        backend.forget(event.getPlayer());
        foreignReported.remove(id);
        placeholders.forget(id);
    }
}
