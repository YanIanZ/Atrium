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
import dev.yanianz.atrium.module.display.TemplateRenderer;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Tab list header/footer, per-group list names and group sorting.
 *
 * <p>Threading: one global timer advances the animation clock, takes a {@link DisplayPlaceholders.ServerView}
 * and (when sorting by name) computes order values; each player is then rendered on their own thread.
 * Packets are only sent when the rendered header/footer, list name or order changed. Events never trigger
 * a full update of every player: a joining player is rendered alone.
 */
public final class TablistModule implements AtriumModule, Listener {
    static final String PERM_REFRESH = "atrium.tablist.refresh";

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
        ctx.listen(this);
        ctx.command(CommandSpec.of(Cmds.literal("tablist", PERM_REFRESH)
                .executes(this::usage)
                .then(Cmds.literal("refresh", PERM_REFRESH).executes(this::refresh))
                .build(), "Tab list administration"));
        startTimer();
    }

    @Override
    public void reload() {
        applyConfig();
        sent.clear();
        startTimer();
    }

    @Override
    public void disable() {
        timer.cancel();
        TablistSettings current = settings;
        if (current != null && ctx.plugin().isEnabled()) {
            // Module disabled at runtime: give players a plain tab list back (on their own threads).
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (sent.containsKey(player.getUniqueId())) {
                    ctx.scheduler().entity(player, () -> clear(player, current), null);
                }
            }
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

    private void startTimer() {
        timer.cancel();
        long period = settings.intervalTicks();
        timer = ctx.track(ctx.scheduler().globalTimer(() -> cycle(period), 1L, period));
    }

    /** Global thread. */
    private void cycle(long period) {
        long tick = clock += period;
        TablistSettings current = settings;
        Map<String, String> frames = current.animations().framesAt(tick);
        DisplayPlaceholders.ServerView view = placeholders.serverView();
        Map<UUID, Integer> orders = current.sortByGroup() && current.sortByDisplayName()
                ? TabOrder.byGroupAndName(entries.values())
                : Map.of();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Integer order = orders.get(player.getUniqueId());
            ctx.scheduler().entity(player, () -> render(player, current, frames, view, order), null);
        }
    }

    /** Player's thread. {@code globalOrder} is null unless sorting by name already ranked this player. */
    private void render(Player player, TablistSettings s, Map<String, String> frames,
            DisplayPlaceholders.ServerView view, Integer globalOrder) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        int groupIndex = s.groupIndex(player::hasPermission);
        entries.put(id, new TabOrder.Entry(id, groupIndex,
                PlainTextComponentSerializer.plainText().serialize(player.displayName())));

        TagResolver values = placeholders.values(player, view).resolver();
        TablistSettings.Texts texts = s.texts(player.getWorld().getName(), player::hasPermission);
        Component header = renderer.render(player, texts.header(), frames, values);
        Component footer = renderer.render(player, texts.footer(), frames, values);
        Sent previous = sent.get(id);
        if (previous == null || !header.equals(previous.header()) || !footer.equals(previous.footer())) {
            // Always send both, also when empty, so an old header is cleared.
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
        Component listName = null;
        if (s.setListName()) {
            listName = renderer.render(player, s.listNameFormat(groupIndex), frames, values);
            if (previous == null || !listName.equals(previous.listName())) {
                player.playerListName(listName);
            }
        }
        if (s.sortByGroup()) {
            int order = globalOrder != null ? globalOrder : TabOrder.groupOrder(groupIndex, s.groups().size());
            if (player.getPlayerListOrder() != order) {
                player.setPlayerListOrder(order);
            }
        }
        sent.put(id, new Sent(header, footer, listName));
    }

    /** Player's thread: removes what this module set. */
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
        Map<String, String> frames = current.animations().framesAt(clock);
        // Only the joining player is rendered now; everyone else is refreshed by the next timer cycle.
        ctx.scheduler().supplyGlobal(placeholders::serverView).thenAccept(view ->
                ctx.scheduler().entity(player, () -> render(player, current, frames, view, null), null))
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
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "tablist.usage");
        return Cmds.OK;
    }

    private int refresh(CommandContext<CommandSourceStack> c) {
        sent.clear();
        var sender = c.getSource().getSender();
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, "tablist.refreshed",
                    Msg.number("players", Bukkit.getOnlinePlayers().size())), null);
        } else {
            ctx.messages().send(sender, "tablist.refreshed", Msg.number("players", Bukkit.getOnlinePlayers().size()));
        }
        return Cmds.OK;
    }
}
