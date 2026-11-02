package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.holograms.HologramService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.holograms.HologramManager.Change;
import dev.yanianz.atrium.module.holograms.HologramManager.Outcome;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntBinaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.permissions.PermissionDefault;

/** Display-entity holograms: {@code /holo}, per-viewer placeholders, pages and click actions. */
public final class HologramsModule implements AtriumModule, HologramService, Listener {
    static final String PERM_MANAGE = "atrium.holograms.manage";
    static final String PERM_ENGINE = "atrium.holograms.engine";
    static final String PERM_CLICK = "atrium.holograms.click";

    private ModuleContext ctx;
    private ConfigHandle<HologramsConfig> config;
    private volatile HologramSettings defaults;
    private HologramManager manager;
    private Displays displays;
    private ViewerTracker tracker;
    private EngineController engines;

    @Override
    public String id() {
        return "holograms";
    }

    @Override
    public String description() {
        return "Holograms: /holo with pages, placeholders and click actions";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(HologramsConfig.class, HologramsConfig::new);
        this.defaults = loadDefaults();
        ctx.migrate(HologramStorage.MIGRATIONS);

        TextRenderer renderer = new TextRenderer(ctx.messages(), ctx.logger());
        this.displays = new Displays(ctx, config::get, renderer, new NamespacedKey(ctx.plugin(), "hologram"));
        this.manager = new HologramManager(new HologramStorage(ctx.database(), ctx.logger()), this::changed);
        List<Hologram> loaded = manager.load(() -> defaults).get(30, TimeUnit.SECONDS);
        this.tracker = new ViewerTracker(ctx, displays, config::get);
        displays.setTracker(tracker);
        this.engines = new EngineController(ctx, config::get, displays, tracker, renderer, () -> manager.all());

        ctx.permission(PERM_MANAGE, PermissionDefault.OP, "Create and edit holograms (/holo)");
        ctx.permission(PERM_ENGINE, PermissionDefault.OP, "Show and migrate the hologram engine (/holo engine)");
        ctx.permission(PERM_CLICK, PermissionDefault.TRUE, "Click holograms that have click actions");

        ctx.listen(this);
        engines.start(loaded);

        HologramCommands commands = new HologramCommands(ctx, config::get, manager, displays, engines, () -> defaults);
        ctx.command(new CommandSpec(commands.build(PERM_MANAGE), "Create and edit holograms", List.of("hologram", "holograms")));
        ctx.service(HologramService.class, this);
        ctx.logger().info("Loaded {} holograms", loaded.size());
    }

    private HologramSettings loadDefaults() {
        return config.get().defaultSettings(
                (key, problem) -> ctx.logger().warn("holograms.yml: default {} ignored ({})", key, problem));
    }

    @Override
    public void reload() {
        defaults = loadDefaults();
        if (engines != null) {
            engines.reload(manager.all());
        }
    }

    @Override
    public void disable() {
        if (engines != null) {
            engines.stop();
        } else if (displays != null) {
            displays.shutdown();
        }
    }

    /** Called by the storage manager after a change was stored; the active engine mirrors it. */
    private void changed(@org.jspecify.annotations.Nullable Hologram before,
            @org.jspecify.annotations.Nullable Hologram after) {
        if (engines != null) {
            engines.apply(before, after);
        }
    }

    // ---- clicks (player's thread) ----

    // Not ignoreCancelled: protection plugins cancel entity interaction in claims, but these click boxes are ours.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        HologramRuntime runtime = displays.interactionOwner(event.getRightClicked().getUniqueId());
        if (runtime == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            click(event.getPlayer(), runtime, ClickAction.Click.RIGHT);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        HologramRuntime runtime = displays.interactionOwner(event.getAttacked().getUniqueId());
        if (runtime == null) {
            return;
        }
        event.setCancelled(true);
        click(event.getPlayer(), runtime, ClickAction.Click.LEFT);
    }

    private void click(Player player, HologramRuntime runtime, ClickAction.Click button) {
        Hologram hologram = runtime.definition();
        HologramSettings s = hologram.settings();
        if (!player.hasPermission(PERM_CLICK) || (!s.permission().isEmpty() && !player.hasPermission(s.permission()))
                || (!s.clickPermission().isEmpty() && !player.hasPermission(s.clickPermission()))) {
            ctx.messages().actionBar(player, "holograms.click-no-permission");
            return;
        }
        List<ClickAction> actions = new ArrayList<>();
        for (ClickAction action : hologram.actions()) {
            if (action.click().matches(button)) {
                actions.add(action);
            }
        }
        if (hologram.actions().isEmpty() && s.clickPages() && hologram.pages().size() > 1) {
            actions.add(new ClickAction(button, button == ClickAction.Click.LEFT ? ClickAction.Type.PREV : ClickAction.Type.NEXT, ""));
        }
        if (actions.isEmpty()) {
            return;
        }
        if (s.clickCooldownMillis() > 0) {
            Duration left = ctx.cooldowns().tryAcquire(ctx.users().user(player), "holograms:click:" + runtime.key,
                    Duration.ofMillis(s.clickCooldownMillis()));
            if (!left.isZero()) {
                ctx.messages().actionBar(player, "holograms.click-cooldown", Msg.duration("time", left));
                return;
            }
        }
        for (ClickAction action : actions) {
            run(player, runtime, action);
        }
    }

    private void run(Player player, HologramRuntime runtime, ClickAction action) {
        switch (action.type()) {
            case CONSOLE -> fill(player, runtime, action).ifPresent(command ->
                    ctx.scheduler().global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)));
            case PLAYER -> fill(player, runtime, action).ifPresent(player::performCommand);
            case MESSAGE -> player.sendMessage(ctx.messages().parseTrusted(action.value(),
                    Msg.unparsed("player", player.getName())));
            case NEXT -> page(player, runtime, Pages::next);
            case PREV -> page(player, runtime, Pages::previous);
            case PAGE -> {
                int target = action.page() - 1;
                page(player, runtime, (current, count) -> target);
            }
        }
    }

    private Optional<String> fill(Player player, HologramRuntime runtime, ClickAction action) {
        Optional<String> command = ClickAction.fill(action.value(), player.getName(), player.getUniqueId());
        if (command.isEmpty()) {
            ctx.logger().warn("Hologram {}: click action '{}' skipped, player name '{}' is not safe to put in a command",
                    runtime.key, action.encode(), player.getName());
        }
        return command;
    }

    /** A viewer with an own copy flips only their copy; everyone else shares the hologram page. */
    private void page(Player player, HologramRuntime runtime, IntBinaryOperator op) {
        if (runtime.hasPersonal(player.getUniqueId())) {
            int count = runtime.layouts().size();
            runtime.setPersonalPage(player.getUniqueId(), op.applyAsInt(runtime.personalPage(player.getUniqueId()), count));
            tracker.rerender(player, runtime);
        } else {
            runtime.changeSharedPage(op);
        }
    }

    // ---- HologramService ----

    @Override
    public Collection<String> names() {
        return manager.all().stream().map(Hologram::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    @Override
    public Optional<HologramInfo> get(String name) {
        return manager.get(name).map(h -> new HologramInfo(h.name(), h.location(), h.rawPages()));
    }

    @Override
    public CompletableFuture<Result> create(String name, StoredLocation location, List<String> lines) {
        if (!Hologram.validName(name)) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        List<HoloLine> page;
        try {
            page = validLines(lines);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        HologramSettings settings = defaults;
        return result(manager.edit(name, current -> current != null ? Change.fail(Outcome.EXISTS)
                : Change.ok(new Hologram(name, location, settings, List.of(page), List.of(), System.currentTimeMillis()))));
    }

    @Override
    public CompletableFuture<Result> delete(String name) {
        return result(manager.edit(name, current -> current == null ? Change.fail(Outcome.NOT_FOUND) : Change.delete()));
    }

    @Override
    public CompletableFuture<Result> addLine(String name, String line) {
        List<HoloLine> added;
        try {
            added = validLines(List.of(line));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        int max = config.get().maxLinesPerPage;
        return result(manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            if (pages.getLast().size() >= max) {
                return Change.fail(Outcome.LIMIT);
            }
            pages.getLast().addAll(added);
            return Change.ok(current.withPages(pages));
        }));
    }

    @Override
    public CompletableFuture<Result> setPage(String name, int page, List<String> lines) {
        List<HoloLine> replacement;
        try {
            replacement = validLines(lines);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        int maxPages = config.get().maxPages;
        return result(manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            if (page < 1 || page > pages.size() + 1) {
                return Change.fail(Outcome.NO_SUCH_PAGE);
            }
            if (page == pages.size() + 1) {
                if (pages.size() >= maxPages) {
                    return Change.fail(Outcome.LIMIT);
                }
                pages.add(new ArrayList<>(replacement));
            } else {
                pages.set(page - 1, new ArrayList<>(replacement));
            }
            return Change.ok(current.withPages(pages));
        }));
    }

    @Override
    public CompletableFuture<Result> move(String name, StoredLocation location) {
        return result(manager.edit(name, current -> current == null ? Change.fail(Outcome.NOT_FOUND)
                : Change.ok(current.withLocation(location))));
    }

    private List<HoloLine> validLines(List<String> lines) {
        HologramsConfig c = config.get();
        if (lines.size() > c.maxLinesPerPage) {
            throw new IllegalArgumentException("too many lines");
        }
        List<HoloLine> out = new ArrayList<>();
        for (String line : lines) {
            out.add(HoloLine.of(HologramCommands.validateLine(line, c.maxLineLength)));
        }
        return out;
    }

    private static CompletableFuture<Result> result(CompletableFuture<Outcome> outcome) {
        return outcome.thenApply(o -> switch (o) {
            case OK, UNCHANGED -> Result.SUCCESS;
            case NOT_FOUND -> Result.NOT_FOUND;
            case EXISTS -> Result.EXISTS;
            default -> Result.INVALID;
        });
    }
}
