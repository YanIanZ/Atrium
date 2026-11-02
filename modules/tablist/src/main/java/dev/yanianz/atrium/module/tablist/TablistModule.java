package dev.yanianz.atrium.module.tablist;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.module.display.DisplayPlaceholders;
import dev.yanianz.atrium.module.display.TabText;
import dev.yanianz.atrium.module.display.TemplateRenderer;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
 *       ({@link TabBridge}); Atrium then sends none of these itself.</li>
 * </ul>
 * In both cases Atrium's templates, groups and permissions are the source of truth and every text is rendered by
 * Atrium, per player.
 *
 * <p>Threading: one global timer advances the animation clock, takes a {@link DisplayPlaceholders.ServerView}
 * and (when sorting by name) computes order values; each player is then rendered on their own thread. Natively,
 * packets are only sent when the rendered header, footer, list name or order changed. With TAB, the rendered
 * texts are recorded and the next global cycle hands only the changes to TAB, so Atrium never calls TAB from a
 * region thread. Engine switches also run on the global thread.
 */
public final class TablistModule implements AtriumModule, Listener {
    static final String PERM_REFRESH = "atrium.tablist.refresh";
    static final String PERM_ENGINE = "atrium.tablist.engine";

    /** What was last sent to a player; only touched on that player's thread. */
    private record Sent(Component header, Component footer, Component listName) {}

    private ModuleContext ctx;
    private ConfigHandle<TablistConfig> config;
    private volatile DisplayPlaceholders placeholders;
    private TemplateRenderer renderer;
    private volatile TablistSettings settings;
    private volatile TaskHandle timer = TaskHandle.NOOP;
    /** Ticks since the module started; only written by the global timer. */
    private volatile long clock;
    private final Map<UUID, Sent> sent = new ConcurrentHashMap<>();
    private final Map<UUID, TabOrder.Entry> entries = new ConcurrentHashMap<>();

    /** Non-null exactly while the TAB engine is active. */
    private volatile @Nullable TabBridge bridge;
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
        ctx.permission(PERM_REFRESH, PermissionDefault.OP, "Re-send the tab list of every player");
        ctx.permission(PERM_ENGINE, PermissionDefault.OP, "Show the tab list engine and what it supports (/tablist engine)");
        ctx.listen(this);
        ctx.listen(new PluginWatcher());
        ctx.command(CommandSpec.of(Cmds.literal("tablist")
                .requires(source -> source.getSender().hasPermission(PERM_REFRESH)
                        || source.getSender().hasPermission(PERM_ENGINE))
                .executes(this::usage)
                .then(Cmds.literal("refresh", PERM_REFRESH).executes(this::refresh))
                .then(Cmds.literal("engine", PERM_ENGINE).executes(this::engineInfo))
                .build(), "Tab list administration"));
        configureEngine();
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
        startTimer();
    }

    @Override
    public void disable() {
        timer.cancel();
        TablistSettings current = settings;
        TabBridge tab = bridge;
        bridge = null;
        if (tab != null) {
            // Remove every override Atrium set in TAB; TAB shows its own configuration again.
            tab.stop();
        } else if (current != null && ctx.plugin().isEnabled()) {
            // Module disabled at runtime: give players a plain tab list back (on their own threads).
            clearNative(current);
        }
        sent.clear();
        entries.clear();
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
            TabBridge created = TabBridgeFactory.create(ctx.logger(), ctx.services());
            if (created != null) {
                useTab(current, created);
                return;
            }
            reason = TablistEngineSelection.START_FAILED;
        }
        useNative(reason);
    }

    private void useTab(@Nullable TabBridge old, TabBridge created) {
        if (old != null) {
            old.stop();
        }
        TablistSettings current = settings;
        boolean wasNative = old == null;
        bridge = created;
        fallbackReason = "";
        if (wasNative) {
            // Take back what the native engine sent, so only TAB shows the player list from now on. Renders
            // queued for the native engine see the changed bridge and skip.
            clearNative(current);
            sent.clear();
        }
        logEngine();
    }

    private void useNative(String reason) {
        TabBridge old = bridge;
        bridge = null;
        if (old != null) {
            old.stop();
            sent.clear();
        }
        fallbackReason = reason;
        logEngine();
    }

    private void logEngine() {
        TablistEngine active = activeEngine();
        String state = active.id + "|" + configured.id + "|" + fallbackReason;
        if (state.equals(loggedState)) {
            return;
        }
        loggedState = state;
        if (!fallbackReason.isEmpty()) {
            ctx.logger().warn("Tab list engine '{}' is unavailable ({}); using native", configured.id, fallbackReason);
        } else {
            ctx.logger().info("Tab list engine: {} (configured: {})", active.id, configured.id);
        }
    }

    private TablistEngine activeEngine() {
        return bridge != null ? TablistEngine.TAB : TablistEngine.NATIVE;
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
                // Hands the texts rendered since the previous cycle to TAB.
                tab.flush(current.sortByGroup() ? teamNames(current) : null);
            } else {
                configureEngine();
                tab = bridge;
            }
        }
        Map<String, String> frames = current.animations().framesAt(tick);
        DisplayPlaceholders.ServerView view = placeholders.serverView();
        Map<UUID, Integer> orders = tab == null && current.sortByGroup() && current.sortByDisplayName()
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

    /**
     * Player's thread. {@code globalOrder} is null unless sorting by name already ranked this player.
     * {@code tab} is the TAB engine this render was scheduled for, or null for native.
     */
    private void render(Player player, TablistSettings s, Map<String, String> frames,
            DisplayPlaceholders.ServerView view, Integer globalOrder, @Nullable TabBridge tab) {
        if (!player.isOnline() || tab != bridge) {
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
                ? renderer.render(player, s.listNameFormat(groupIndex), frames, values)
                : null;
        if (tab != null) {
            // TAB shows it: convert once here, the next global cycle hands over what changed.
            tab.offer(id, new TabBridge.Frame(TabText.toTab(header), TabText.toTab(footer),
                    listName == null ? null : TabText.toTab(listName)));
            return;
        }
        Sent previous = sent.get(id);
        if (previous == null || !header.equals(previous.header()) || !footer.equals(previous.footer())) {
            // Always send both, also when empty, so an old header is cleared.
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
        if (listName != null && (previous == null || !listName.equals(previous.listName()))) {
            player.playerListName(listName);
        }
        if (s.sortByGroup()) {
            int order = globalOrder != null ? globalOrder : TabOrder.groupOrder(groupIndex, s.groups().size());
            if (player.getPlayerListOrder() != order) {
                player.setPlayerListOrder(order);
            }
        }
        sent.put(id, new Sent(header, footer, listName));
    }

    /** Schedules {@link #clear} for every player the native engine sent something to. */
    private void clearNative(TablistSettings s) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sent.containsKey(player.getUniqueId())) {
                ctx.scheduler().entity(player, () -> clear(player, s), null);
            }
        }
    }

    /** Player's thread: removes what the native engine set. */
    private void clear(Player player, TablistSettings s) {
        if (!player.isOnline()) {
            return;
        }
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        if (s.setListName()) {
            player.playerListName(null);
        }
        if (s.sortByGroup()) {
            player.setPlayerListOrder(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        TablistSettings current = settings;
        TabBridge tab = bridge;
        Map<String, String> frames = current.animations().framesAt(clock);
        // Only the joining player is rendered now; everyone else is refreshed by the next timer cycle.
        ctx.scheduler().supplyGlobal(placeholders::serverView).thenAccept(view ->
                ctx.scheduler().entity(player, () -> render(player, current, frames, view, null, tab), null))
                .exceptionally(error -> {
                    ctx.logger().warn("Rendering the tab list of {} after join failed", player.getName(), error);
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sent.remove(id);
        entries.remove(id);
        placeholders.forget(id);
        TabBridge tab = bridge;
        if (tab != null) {
            tab.forget(id);
        }
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
        TabBridge tab = bridge;
        TablistEngine active = tab != null ? TablistEngine.TAB : TablistEngine.NATIVE;
        TablistCapabilities capabilities = tab != null ? tab.capabilities() : TablistCapabilities.nativeEngine();
        List<String> available = new ArrayList<>(List.of(TablistEngine.NATIVE.id));
        if (TabBridgeFactory.pluginEnabled()) {
            available.add(TablistEngine.TAB.id);
        }
        String reason = fallbackReason;
        reply(sender, "tablist.engine-header", Msg.unparsed("engine", active.id),
                Msg.unparsed("configured", configured.id));
        reply(sender, "tablist.engine-capabilities",
                Msg.unparsed("supported", TablistCapabilities.describe(capabilities.supported())));
        if (capabilities.missing().isEmpty()) {
            reply(sender, "tablist.engine-all-capabilities");
        } else {
            reply(sender, "tablist.engine-capabilities-missing",
                    Msg.unparsed("missing", TablistCapabilities.describe(capabilities.missing())));
            reply(sender, "tablist.engine-tab-note");
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
        return Cmds.OK;
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
