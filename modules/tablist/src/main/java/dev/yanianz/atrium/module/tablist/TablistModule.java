package dev.yanianz.atrium.module.tablist;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.tablist.TabListService;
import dev.yanianz.atrium.api.vanish.VanishStateChangeEvent;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.module.display.DisplayPlaceholders;
import dev.yanianz.atrium.module.display.TabResync;
import dev.yanianz.atrium.module.display.TabText;
import dev.yanianz.atrium.module.display.TemplateRenderer;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.jspecify.annotations.Nullable;

/**
 * Tab list header/footer, per-group list names and group sorting, shown by one of two engines:
 * <ul>
 *   <li><b>native</b>: Atrium sends the header/footer, list names and list order itself;</li>
 *   <li><b>tab</b>: the TAB plugin owns the player list and Atrium feeds it through TAB's API
 *       ({@link TabBridge}). Atrium's configuration stays the only source of truth: every part Atrium manages
 *       overrides TAB's own content, and a part whose TAB feature is off is sent by Atrium itself
 *       ({@link TabOwnership}).</li>
 * </ul>
 * Every text is rendered by Atrium, per player. Other modules add tags such as {@code [AFK]} to the rendered name
 * through {@link TabListService} instead of setting list names themselves.
 *
 * <p>Threading: one global timer advances the animation clock, takes a {@link DisplayPlaceholders.ServerView}
 * and (when sorting by name) computes order values; each player is then rendered on their own thread. Natively,
 * packets are only sent when the rendered header, footer, list name or order changed. With TAB, the rendered
 * texts are recorded and handed to TAB on the global thread only (never from a region thread): right away when
 * TAB loads a player or reloads ({@link TabResync}), and otherwise at the next flush. Engine switches also run on
 * the global thread.
 */
public final class TablistModule implements AtriumModule, Listener, TabListService {
    static final String PERM_REFRESH = "atrium.tablist.refresh";
    static final String PERM_ENGINE = "atrium.tablist.engine";
    static final String PERM_DEBUG = "atrium.tablist.debug";

    /**
     * What the native path last sent to a player; only touched on that player's thread. {@code null} parts were
     * not sent natively (TAB shows them, or Atrium does not manage them).
     */
    private record Sent(@Nullable Component header, @Nullable Component footer, @Nullable Component listName,
            @Nullable Integer order) {}

    private ModuleContext ctx;
    private ConfigHandle<TablistConfig> config;
    private volatile DisplayPlaceholders placeholders;
    private TemplateRenderer renderer;
    private volatile TablistSettings settings;
    private volatile TaskHandle timer = TaskHandle.NOOP;
    private volatile boolean enabled;
    /** Ticks since the module started; only written by the global timer. */
    private volatile long clock;
    private final Map<UUID, Sent> sent = new ConcurrentHashMap<>();
    private final Map<UUID, TabOrder.Entry> entries = new ConcurrentHashMap<>();
    private final NameTags nameTags = new NameTags();

    /** Non-null exactly while the TAB engine is active. */
    private volatile @Nullable TabBridge bridge;
    /** Belongs to {@link #bridge}; closed when that bridge stops. */
    private volatile @Nullable TabResync resync;
    private volatile TablistEngine configured = TablistEngine.AUTO;
    /** Empty when the configured engine is used as configured. */
    private volatile String fallbackReason = "";
    /** Engine and reason of the last log line, so a reload does not repeat it. */
    private volatile String loggedState = "";

    @Override
    public String id() {
        return "tablist";
    }

    @Override
    public String description() {
        return "Tab list header/footer, group list names and sorting";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(TablistConfig.class, TablistConfig::new);
        this.renderer = new TemplateRenderer(ctx.messages(), ctx.logger());
        applyConfig();
        enabled = true;
        ctx.permission(PERM_REFRESH, PermissionDefault.OP, "Re-send the tab list of every player");
        ctx.permission(PERM_ENGINE, PermissionDefault.OP,
                "Show the tab list engine and who owns each part of the tab list (/tablist engine)");
        ctx.permission(PERM_DEBUG, PermissionDefault.OP,
                "Compare what Atrium set in TAB for a player with what TAB shows (/tablist debug)");
        ctx.listen(this);
        ctx.listen(new PluginWatcher());
        ctx.command(CommandSpec.of(Cmds.literal("tablist")
                .requires(source -> source.getSender().hasPermission(PERM_REFRESH)
                        || source.getSender().hasPermission(PERM_ENGINE)
                        || source.getSender().hasPermission(PERM_DEBUG))
                .executes(this::usage)
                .then(Cmds.literal("refresh", PERM_REFRESH).executes(this::refresh))
                .then(Cmds.literal("engine", PERM_ENGINE).executes(this::engineInfo))
                .then(Cmds.literal("debug", PERM_DEBUG)
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(this::debug)))
                .build(), "Tab list administration"));
        configureEngine();
        ctx.service(TabListService.class, this);
        startTimer();
    }

    @Override
    public void reload() {
        applyConfig();
        sent.clear();
        TabBridge current = bridge;
        if (current != null) {
            current.resendAll();
        }
        // Reload runs on the sender's thread; engine switches call into TAB and belong to the global thread.
        ctx.track(ctx.scheduler().global(this::configureEngine));
        // The first cycle runs one tick from now and renders (and hands over) every player with the new config.
        startTimer();
    }

    @Override
    public void disable() {
        enabled = false;
        timer.cancel();
        TablistSettings current = settings;
        TabBridge tab = bridge;
        bridge = null;
        closeResync();
        if (tab != null) {
            // Remove every override Atrium set in TAB; TAB shows its own configuration again.
            tab.stop();
        }
        if (current != null && ctx.plugin().isEnabled()) {
            // Module disabled at runtime: take back what Atrium sent itself (on the players' own threads).
            clearNative();
        }
        sent.clear();
        entries.clear();
        nameTags.clearAll();
        placeholders.clear();
    }

    private void applyConfig() {
        TablistConfig raw = config.get();
        BiConsumer<String, String> warn = (option, problem) -> ctx.logger().warn("tablist.yml {}: {}", option, problem);
        TablistSettings next = TablistSettings.from(raw, warn);
        int refresh = TablistSettings.clamp(raw.asyncRefreshSeconds, 5, 3600, "async-refresh-seconds", warn);
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

    // ---- TabListService (any thread) ----

    @Override
    public Engine engine() {
        return bridge != null ? Engine.TAB : Engine.NATIVE;
    }

    @Override
    public void setNameTag(UUID player, String source, Placement placement, @Nullable Component tag) {
        if (nameTags.set(player, source, placement, tag)) {
            requestRender(player);
        }
    }

    @Override
    public void clearNameTags(String source) {
        for (UUID player : nameTags.clear(source)) {
            requestRender(player);
        }
    }

    @Override
    public void requestRender(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && enabled) {
            renderSoon(player);
        }
    }

    // ---- engine selection (global thread, or enable) ----

    private TablistEngine readConfigured() {
        String raw = config.get().engine;
        return TablistEngine.parse(raw).orElseGet(() -> {
            ctx.logger().warn("tablist.yml engine: unknown engine '{}'; using auto", raw);
            return TablistEngine.AUTO;
        });
    }

    /** Picks the engine from the configuration and switches to it when it changed. */
    private void configureEngine() {
        if (!enabled) {
            return;
        }
        TablistEngine wanted = readConfigured();
        configured = wanted;
        TablistEngineSelection.Selection selection =
                TablistEngineSelection.select(wanted, TabBridgeFactory.pluginEnabled());
        String reason = selection.reason();
        if (selection.engine() == TablistEngine.TAB) {
            TabBridge current = bridge;
            if (current != null && current.usable()) {
                fallbackReason = "";
                logEngine();
                return;
            }
            TabResync next = new TabResync(task -> ctx.track(ctx.scheduler().global(task)), this::onTabResync);
            TabBridge created = TabBridgeFactory.create(ctx.logger(), ctx.services(), next);
            if (created != null) {
                useTab(current, created, next);
                return;
            }
            next.close();
            reason = TablistEngineSelection.START_FAILED;
        }
        useNative(reason);
    }

    private void useTab(@Nullable TabBridge old, TabBridge created, TabResync next) {
        closeResync();
        if (old != null) {
            old.stop();
        }
        bridge = created;
        resync = next;
        fallbackReason = "";
        // The next render of each player hands their texts to TAB and takes back the parts the native engine
        // sent that TAB now shows (see render); TAB itself overwrites the header it takes over.
        next.wake();
        logEngine();
    }

    private void useNative(String reason) {
        TabBridge old = bridge;
        bridge = null;
        closeResync();
        if (old != null) {
            old.stop();
        }
        fallbackReason = reason;
        logEngine();
    }

    private void closeResync() {
        TabResync current = resync;
        resync = null;
        if (current != null) {
            current.close();
        }
    }

    private void logEngine() {
        TablistEngine active = activeEngine();
        boolean doubled = active == TablistEngine.NATIVE && TabBridgeFactory.pluginEnabled();
        String state = active.id + "|" + configured.id + "|" + fallbackReason + "|" + doubled;
        if (state.equals(loggedState)) {
            return;
        }
        loggedState = state;
        if (!fallbackReason.isEmpty()) {
            ctx.logger().warn("Tab list engine '{}' is unavailable ({}); using native", configured.id, fallbackReason);
        } else {
            ctx.logger().info("Tab list engine: {} (configured: {})", active.id, configured.id);
        }
        if (doubled) {
            ctx.logger().warn("The TAB plugin is enabled but the tab list engine is native: TAB and Atrium both draw "
                    + "the tab list and their content mixes. Set engine: auto or tab in tablist.yml.");
        }
    }

    private TablistEngine activeEngine() {
        return bridge != null ? TablistEngine.TAB : TablistEngine.NATIVE;
    }

    private static TabOwnership ownership(TablistSettings s, @Nullable TabBridge tab) {
        return TabOwnership.plan(tab != null, tab != null ? tab.capabilities() : TablistCapabilities.nativeEngine(),
                s.setListName(), s.sortByGroup());
    }

    /**
     * Global thread: TAB loaded players or reloaded (their overrides are gone), or new texts are waiting. Hands
     * Atrium's values over right away, then re-renders the reloaded players so new joiners are covered too.
     */
    private void onTabResync(TabResync.Batch batch) {
        TabBridge tab = bridge;
        if (tab == null || !enabled) {
            return;
        }
        if (!tab.usable()) {
            configureEngine();
            return;
        }
        TablistSettings current = settings;
        tab.invalidate(batch);
        tab.flush(current.sortByGroup() ? teamNames(current) : null);
        if (batch.onlyWake()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (batch.affects(player.getUniqueId())) {
                // TAB may have cleared what Atrium sends itself for parts TAB does not run (for example the
                // header after header-footer was turned off): send those again too.
                sent.remove(player.getUniqueId());
                renderSoon(player);
            }
        }
    }

    /** Falls back to native when TAB goes away, and returns when it comes back. */
    final class PluginWatcher implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onDisable(PluginDisableEvent event) {
            if (Bukkit.isStopping() || !ctx.plugin().isEnabled()
                    || !TablistEngineSelection.fallBackOnDisable(activeEngine(), event.getPlugin().getName())) {
                return;
            }
            TabBridge old = bridge;
            bridge = null;
            closeResync();
            if (old != null) {
                // Never call into a plugin that is being disabled; its overrides go away with it.
                old.detach();
            }
            sent.clear();
            fallbackReason = TablistEngineSelection.PLUGIN_DISABLED;
            ctx.logger().warn("TAB was disabled; the tab list switches to the native engine");
            logEngine();
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnable(PluginEnableEvent event) {
            String plugin = event.getPlugin().getName();
            if (Bukkit.isStopping() || !ctx.plugin().isEnabled() || !TablistEngineSelection.returnOnEnable(
                    activeEngine(), configured, fallbackReason, plugin)) {
                return;
            }
            ctx.logger().info("TAB was enabled; the tab list moves back to the TAB engine");
            // Next global tick: TAB finishes enabling first.
            ctx.track(ctx.scheduler().global(TablistModule.this::configureEngine));
        }
    }

    // ---- rendering ----

    private void startTimer() {
        timer.cancel();
        long period = settings.intervalTicks();
        timer = ctx.track(ctx.scheduler().globalTimer(() -> cycle(period), 1L, period));
    }

    /** Global thread. */
    private void cycle(long period) {
        long tick = clock += period;
        TablistSettings current = settings;
        TabBridge tab = bridge;
        if (tab != null) {
            if (tab.usable()) {
                // Hands over what was rendered since the last hand-over (normally already done by the resync).
                tab.flush(current.sortByGroup() ? teamNames(current) : null);
            } else {
                configureEngine();
                tab = bridge;
            }
        }
        Map<String, String> frames = current.animations().framesAt(tick);
        DisplayPlaceholders.ServerView view = placeholders.serverView();
        Map<UUID, Integer> orders = ownership(current, tab).sorting().nativeAtrium() && current.sortByDisplayName()
                ? TabOrder.byGroupAndName(entries.values())
                : Map.of();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Integer order = orders.get(player.getUniqueId());
            TabBridge engine = tab;
            ctx.scheduler().entity(player, () -> render(player, current, frames, view, order, engine), null);
        }
    }

    /** Global thread: TAB team names from each player's last rendered group and name. */
    private Map<UUID, String> teamNames(TablistSettings s) {
        List<TabTeamNames.Member> members = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            TabOrder.Entry entry = entries.get(player.getUniqueId());
            if (entry != null) {
                // Without name sorting the client order inside a group is the account name, as natively.
                String key = s.sortByDisplayName() ? entry.sortName() : player.getName();
                members.add(new TabTeamNames.Member(entry.id(), entry.groupIndex(), key));
            }
        }
        return TabTeamNames.assign(members);
    }

    /** Any thread: renders one player soon (join, TAB load, name tag or nickname change). */
    private void renderSoon(Player player) {
        TablistSettings current = settings;
        Map<String, String> frames = current.animations().framesAt(clock);
        ctx.scheduler().supplyGlobal(placeholders::serverView).thenAccept(view -> {
            // Read the engine on the global thread, after any engine switch queued before this render.
            TabBridge tab = bridge;
            ctx.scheduler().entity(player, () -> render(player, current, frames, view, null, tab), null);
        }).exceptionally(error -> {
            ctx.logger().warn("Rendering the tab list of {} failed", player.getName(), error);
            return null;
        });
    }

    /**
     * Player's thread. {@code globalOrder} is null unless sorting by name already ranked this player.
     * {@code tab} is the TAB engine this render was scheduled for, or null for native.
     */
    private void render(Player player, TablistSettings s, Map<String, String> frames,
            DisplayPlaceholders.ServerView view, Integer globalOrder, @Nullable TabBridge tab) {
        if (!player.isOnline() || tab != bridge || !enabled) {
            return; // the engine changed since this render was scheduled
        }
        UUID id = player.getUniqueId();
        int groupIndex = s.groupIndex(player::hasPermission);
        entries.put(id, new TabOrder.Entry(id, groupIndex,
                PlainTextComponentSerializer.plainText().serialize(player.displayName())));

        TagResolver values = placeholders.values(player, view).resolver();
        TablistSettings.Texts texts = s.texts(player.getWorld().getName(), player::hasPermission);
        Component header = renderer.render(player, texts.header(), frames, values);
        Component footer = renderer.render(player, texts.footer(), frames, values);
        Component listName = s.setListName()
                ? nameTags.compose(id, renderer.render(player, s.listNameFormat(groupIndex), frames, values))
                : null;
        TabOwnership own = ownership(s, tab);
        if (tab != null) {
            // Converted once here; handed to TAB on the global thread.
            tab.offer(id, new TabBridge.Frame(TabText.toTab(header), TabText.toTab(footer),
                    listName == null ? null : TabText.toTab(listName)));
        }
        Sent previous = sent.get(id);
        Component sentHeader = null;
        Component sentFooter = null;
        Component sentName = null;
        Integer sentOrder = null;
        if (own.headerFooter().nativeAtrium()) {
            if (previous == null || previous.header() == null
                    || !header.equals(previous.header()) || !footer.equals(previous.footer())) {
                // Always send both, also when empty, so an old header is cleared.
                player.sendPlayerListHeaderAndFooter(header, footer);
            }
            sentHeader = header;
            sentFooter = footer;
        }
        if (own.listNames().nativeAtrium() && listName != null) {
            if (previous == null || !listName.equals(previous.listName())) {
                player.playerListName(listName);
            }
            sentName = listName;
        } else if (previous != null && previous.listName() != null) {
            player.playerListName(null); // TAB (or vanilla) shows the name now
        }
        if (own.sorting().nativeAtrium()) {
            int order = globalOrder != null ? globalOrder : TabOrder.groupOrder(groupIndex, s.groups().size());
            if (player.getPlayerListOrder() != order) {
                player.setPlayerListOrder(order);
            }
            sentOrder = order;
        } else if ((own.sorting().viaTab() || previous != null && previous.order() != null)
                && player.getPlayerListOrder() != 0) {
            // The client sorts by list order before team names: a leftover order would beat TAB's sorting.
            player.setPlayerListOrder(0);
        }
        sent.put(id, new Sent(sentHeader, sentFooter, sentName, sentOrder));
    }

    /** Schedules {@link #clear} for every player the native path sent something to. */
    private void clearNative() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Sent previous = sent.get(player.getUniqueId());
            if (previous != null) {
                ctx.scheduler().entity(player, () -> clear(player, previous), null);
            }
        }
    }

    /** Player's thread: removes what the native path set. */
    private static void clear(Player player, Sent previous) {
        if (!player.isOnline()) {
            return;
        }
        if (previous.header() != null) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        if (previous.listName() != null) {
            player.playerListName(null);
        }
        if (previous.order() != null) {
            player.setPlayerListOrder(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Only the joining player is rendered now; everyone else is refreshed by the next timer cycle. With TAB,
        // TAB's PlayerLoadEvent hands this to TAB as soon as TAB has loaded the player.
        renderSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sent.remove(id);
        entries.remove(id);
        nameTags.forget(id);
        placeholders.forget(id);
        TabBridge tab = bridge;
        if (tab != null) {
            tab.forget(id);
        }
    }

    /** Vanishing changes what other players count and see: refresh everyone soon instead of next cycle. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanish(VanishStateChangeEvent event) {
        // The event fires before the change applies; render one tick later.
        ctx.track(ctx.scheduler().globalLater(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                renderSoon(player);
            }
        }, 1L));
    }

    // ---- commands ----

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "tablist.usage");
        return Cmds.OK;
    }

    private int refresh(CommandContext<CommandSourceStack> c) {
        sent.clear();
        TabBridge tab = bridge;
        if (tab != null) {
            tab.resendAll();
        }
        reply(c.getSource().getSender(), "tablist.refreshed", Msg.number("players", Bukkit.getOnlinePlayers().size()));
        return Cmds.OK;
    }

    private int engineInfo(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        // TAB is only called from the global thread.
        ctx.track(ctx.scheduler().global(() -> sendEngineInfo(sender)));
        return Cmds.OK;
    }

    /** Global thread. */
    private void sendEngineInfo(CommandSender sender) {
        TabBridge tab = bridge;
        TablistEngine active = tab != null ? TablistEngine.TAB : TablistEngine.NATIVE;
        TablistSettings current = settings;
        TabOwnership own = ownership(current, tab);
        List<String> available = new ArrayList<>(List.of(TablistEngine.NATIVE.id));
        if (TabBridgeFactory.pluginEnabled()) {
            available.add(TablistEngine.TAB.id);
        }
        String reason = fallbackReason;
        reply(sender, "tablist.engine-header", Msg.unparsed("engine", active.id),
                Msg.unparsed("configured", configured.id));
        owner(sender, "header-footer", own.headerFooter());
        owner(sender, "list-names", own.listNames());
        owner(sender, "sorting", own.sorting());
        owner(sender, "nametags", own.nametags());
        if (tab != null) {
            TabBridge.Status status = tab.status();
            reply(sender, "tablist.engine-applied", Msg.number("applied", status.appliedPlayers()),
                    Msg.number("loaded", status.loadedPlayers()), Msg.number("reapplied", status.reapplied()));
            reply(sender, "tablist.engine-tab-scope-note");
            if (status.layout()) {
                reply(sender, "tablist.engine-layout-note");
            }
        } else if (TabBridgeFactory.pluginEnabled()) {
            reply(sender, "tablist.engine-native-with-tab");
        }
        reply(sender, "tablist.engine-available", Msg.unparsed("engines", String.join(", ", available)));
        if (!reason.isEmpty()) {
            String key = switch (reason) {
                case TablistEngineSelection.PLUGIN_MISSING -> "tablist.engine-reason-plugin-missing";
                case TablistEngineSelection.PLUGIN_DISABLED -> "tablist.engine-reason-plugin-disabled";
                default -> "tablist.engine-reason-start-failed";
            };
            reply(sender, "tablist.engine-fallback-note", Msg.component("reason", ctx.messages().render(sender, key)));
        }
    }

    private void owner(CommandSender sender, String part, TabOwnership.Owner owner) {
        reply(sender, "tablist.engine-owner", Msg.unparsed("part", part),
                Msg.component("owner", ctx.messages().render(sender, "tablist.owner-" + owner.key())));
    }

    private int debug(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player target = ctx.commands().argPlayer(c, "player");
        UUID id = target.getUniqueId();
        String name = target.getName();
        ctx.track(ctx.scheduler().global(() -> sendDebug(sender, id, name)));
        return Cmds.OK;
    }

    /** Global thread. */
    private void sendDebug(CommandSender sender, UUID id, String name) {
        TabBridge tab = bridge;
        reply(sender, "tablist.debug-header", Msg.unparsed("player", name),
                Msg.unparsed("engine", tab != null ? TablistEngine.TAB.id : TablistEngine.NATIVE.id));
        if (tab == null) {
            reply(sender, "tablist.debug-native");
            return;
        }
        List<TabBridge.DebugRow> rows = tab.debug(id);
        if (rows.isEmpty()) {
            reply(sender, "tablist.debug-not-loaded", Msg.unparsed("player", name));
            return;
        }
        int mismatches = 0;
        for (TabBridge.DebugRow row : rows) {
            String status = !row.readable() ? "tablist.debug-no-readback"
                    : row.atrium() == null ? "tablist.debug-unmanaged"
                    : row.mismatch() ? "tablist.debug-mismatch"
                    : "tablist.debug-ok";
            if (row.mismatch()) {
                mismatches++;
            }
            reply(sender, "tablist.debug-row", Msg.unparsed("part", row.part()),
                    Msg.unparsed("atrium", show(row.atrium())), Msg.unparsed("now", show(row.tabNow())),
                    Msg.unparsed("config", show(row.tabConfig())),
                    Msg.component("status", ctx.messages().render(sender, status)));
        }
        reply(sender, mismatches == 0 ? "tablist.debug-clean" : "tablist.debug-stacked",
                Msg.number("count", mismatches));
    }

    /** Raw TAB text, quoted so empty and missing values are visible. */
    private static String show(@Nullable String value) {
        return value == null ? "-" : "\"" + value + "\"";
    }

    /** Messages to a player go out on the player's own thread. */
    private void reply(CommandSender sender, String key, TagResolver... resolvers) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, resolvers), null);
        } else {
            ctx.messages().send(sender, key, resolvers);
        }
    }
}
