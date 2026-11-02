package dev.yanianz.atrium.module.portals;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.api.portals.PortalUseEvent.TargetType;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;

/** Admin-defined portal regions: /portal. Region entry is handled by {@link PortalListener}. */
public final class PortalsModule implements AtriumModule {
    static final String PERM_ADMIN = "atrium.portal.admin";
    static final String PERM_COMMAND = "atrium.portal.command";

    private ModuleContext ctx;
    private Cmds cmds;
    private Messages messages;
    private ConfigHandle<PortalsConfig> config;
    private PortalRegistry registry;
    private final EntryTracker tracker = new EntryTracker();
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private NamespacedKey wandKey;
    private volatile Material wandMaterial = Material.BLAZE_ROD;
    private volatile Particle outlineParticle = Particle.HAPPY_VILLAGER;
    private TaskHandle outlineTask = TaskHandle.NOOP;

    @Override
    public String id() {
        return "portals";
    }

    @Override
    public String description() {
        return "Portal regions: /portal";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("teleport", "warps", "actions");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.config = ctx.config(PortalsConfig.class, PortalsConfig::new);
        this.wandKey = new NamespacedKey(ctx.plugin(), "portal_wand");
        ctx.migrate(PortalStorage.MIGRATIONS);
        this.registry = new PortalRegistry(new PortalStorage(ctx.database(), ctx.logger()));
        int loaded = registry.load().get(30, TimeUnit.SECONDS);
        applyConfig();

        ctx.permission(PERM_ADMIN, PermissionDefault.OP, "Create, edit and delete portals (/portal)");
        ctx.permission(PERM_COMMAND, PermissionDefault.OP,
                "Attach action lines to portals (they can run console commands)");
        ctx.listen(new PortalListener(this, ctx));
        registerCommand();
        startOutline();
        ctx.logger().info("Loaded {} portals", loaded);
    }

    @Override
    public void reload() throws Exception {
        applyConfig();
        startOutline();
    }

    @Override
    public void disable() {
        outlineTask.cancel();
        selections.clear();
        tracker.clearAll();
    }

    private void applyConfig() {
        PortalsConfig c = config.get();
        Material material = Material.matchMaterial(c.wandMaterial);
        if (material == null || !material.isItem() || material.isAir()) {
            ctx.logger().warn("Unknown wand material '{}' in portals.yml; using BLAZE_ROD", c.wandMaterial);
            material = Material.BLAZE_ROD;
        }
        wandMaterial = material;
        outlineParticle = PortalOutline.particle(c.outline.particle, ctx.logger());
    }

    // ---- accessors for the listener ----

    PortalsConfig config() {
        return config.get();
    }

    PortalRegistry registry() {
        return registry;
    }

    EntryTracker tracker() {
        return tracker;
    }

    Map<UUID, Selection> selections() {
        return selections;
    }

    boolean isWand(ItemStack item) {
        return item != null && item.getType() == wandMaterial
                && item.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    // ---- outline ----

    private void startOutline() {
        outlineTask.cancel();
        PortalsConfig.Outline o = config.get().outline;
        if (!o.enabled) {
            outlineTask = TaskHandle.NOOP;
            return;
        }
        long period = Math.clamp(o.intervalTicks, 5, 100);
        outlineTask = ctx.track(ctx.scheduler().globalTimer(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(PERM_ADMIN)) {
                    // Inventory and particles belong to the viewer's own thread.
                    ctx.scheduler().entity(player, () -> drawOutline(player), null);
                }
            }
        }, period, period));
    }

    private void drawOutline(Player player) {
        if (!isWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        PortalsConfig.Outline o = config.get().outline;
        double distance = Math.clamp(o.viewDistance, 4, 128);
        double spacing = Math.clamp(o.spacing, 0.25, 4.0);
        int budget = Math.clamp(o.maxParticles, 50, 5000);
        Location here = player.getLocation();
        String world = player.getWorld().getName();
        Selection selection = selections.get(player.getUniqueId());
        if (selection != null) {
            Optional<Cuboid> area = selection.area();
            if (area.isPresent() && area.get().world().equals(world)) {
                budget -= PortalOutline.draw(player, area.get(), Particle.END_ROD, spacing, budget / 2, distance);
            }
        }
        double maxSq = distance * distance;
        for (Portal portal : registry.index().inWorld(world)) {
            if (budget <= 0) {
                return;
            }
            if (portal.area().distanceSquared(here.getX(), here.getY(), here.getZ()) <= maxSq) {
                budget -= PortalOutline.draw(player, portal.area(), outlineParticle, spacing, budget, distance);
            }
        }
    }

    // ---- commands ----

    private void registerCommand() {
        ctx.command(CommandSpec.of(Cmds.literal("portal", PERM_ADMIN)
                .executes(this::list)
                .then(Commands.literal("wand").executes(this::wand))
                .then(Commands.literal("pos1").executes(c -> pos(c, true)))
                .then(Commands.literal("pos2").executes(c -> pos(c, false)))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(this::create)))
                .then(Commands.literal("delete").then(nameArg().executes(this::delete)))
                .then(Commands.literal("info").then(nameArg().executes(this::info)))
                .then(Commands.literal("settarget").then(nameArg()
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .suggests(Cmds.suggest(() -> List.of("none", "location", "warp:", "server:")))
                                .executes(this::setTarget))))
                .then(Commands.literal("setcommand").requires(Cmds.permission(PERM_COMMAND)).then(nameArg()
                        .executes(c -> setCommand(c, null))
                        .then(Commands.argument("line", StringArgumentType.greedyString())
                                .executes(c -> setCommand(c, StringArgumentType.getString(c, "line"))))))
                .then(Commands.literal("setpermission").then(nameArg()
                        .then(Commands.argument("permission", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("none")))
                                .executes(this::setPermission))))
                .build(), "Create and manage portal regions and where they lead", "portals"));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> nameArg() {
        return Commands.argument("name", StringArgumentType.word()).suggests(Cmds.suggest(() -> registry.names()));
    }

    private int wand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ItemStack wand = ItemStack.of(wandMaterial);
        wand.editMeta(meta -> meta.displayName(messages.render(player, "portals.wand-name")));
        wand.editPersistentDataContainer(pdc -> pdc.set(wandKey, PersistentDataType.BYTE, (byte) 1));
        // Commands run on the sender's thread, which owns the player's inventory.
        if (!player.getInventory().addItem(wand).isEmpty()) {
            throw cmds.fail(c.getSource(), "portals.inventory-full");
        }
        messages.send(player, "portals.wand-given");
        return Cmds.OK;
    }

    private int pos(CommandContext<CommandSourceStack> c, boolean first) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Location l = player.getLocation();
        setCorner(player, first, new Selection.Corner(player.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
        return Cmds.OK;
    }

    /** Stores a selection corner and tells the admin. Call on the admin's thread. */
    void setCorner(Player player, boolean first, Selection.Corner corner) {
        Selection updated = selections.merge(player.getUniqueId(),
                first ? Selection.EMPTY.withPos1(corner) : Selection.EMPTY.withPos2(corner),
                (old, fresh) -> first ? old.withPos1(corner) : old.withPos2(corner));
        String size = updated.area().map(a -> String.format(Locale.ROOT, "%dx%dx%d", a.sizeX(), a.sizeY(), a.sizeZ()))
                .orElse("-");
        messages.send(player, "portals.pos-set", Msg.unparsed("pos", first ? "1" : "2"),
                Msg.unparsed("world", corner.world()), Msg.number("x", corner.x()), Msg.number("y", corner.y()),
                Msg.number("z", corner.z()), Msg.unparsed("size", size));
    }

    private int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        if (!Portal.validName(name)) {
            throw cmds.fail(c.getSource(), "portals.invalid-name");
        }
        Selection selection = selections.getOrDefault(player.getUniqueId(), Selection.EMPTY);
        if (selection.pos1() == null || selection.pos2() == null) {
            throw cmds.fail(c.getSource(), "portals.no-selection");
        }
        Cuboid area = selection.area().orElseThrow(() -> cmds.fail(c.getSource(), "portals.selection-worlds"));
        int maxSide = Math.clamp(config.get().maxSideLength, 1, 512);
        if (area.maxSide() > maxSide) {
            throw cmds.fail(c.getSource(), "portals.too-large", Msg.number("max", maxSide));
        }
        Portal portal = new Portal(name, area, PortalTarget.NONE, null, null, player.getUniqueId(), System.currentTimeMillis());
        report(player, name, registry.create(portal, config.get().allowOverlap), result -> switch (result.outcome()) {
            case CREATED -> {
                messages.send(player, "portals.created", Msg.unparsed("portal", name), Msg.unparsed("area", area.describe()));
                yield true;
            }
            case EXISTS -> send(player, "portals.exists", name);
            case OVERLAP -> {
                messages.send(player, "portals.overlap", Msg.unparsed("portal", name),
                        Msg.unparsed("other", result.portal().map(Portal::name).orElse("?")));
                yield true;
            }
            default -> send(player, "portals.not-found", name);
        });
        return Cmds.OK;
    }

    private int delete(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        report(sender, name, registry.delete(name), result -> result.outcome() == PortalRegistry.Outcome.DELETED
                ? send(sender, "portals.deleted", result.portal().map(Portal::name).orElse(name))
                : send(sender, "portals.not-found", name));
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<Portal> all = new ArrayList<>(registry.all());
        all.sort(Comparator.comparing(Portal::key));
        messages.send(sender, "portals.list-header", Msg.number("count", all.size()));
        if (all.isEmpty()) {
            messages.send(sender, "portals.list-empty");
        }
        for (Portal portal : all) {
            messages.send(sender, "portals.list-entry", Msg.unparsed("portal", portal.name()),
                    Msg.unparsed("world", portal.area().world()), Msg.unparsed("target", portal.target().describe()));
        }
        return Cmds.OK;
    }

    private int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        Portal portal = registry.get(name).orElseThrow(() -> cmds.fail(c.getSource(), "portals.not-found",
                Msg.unparsed("portal", name)));
        Cuboid a = portal.area();
        String none = "-";
        String creator = portal.creator() == null ? none : ctx.users().cached(portal.creator()).map(AtriumUser::name)
                .orElse(portal.creator().toString());
        messages.send(sender, "portals.info", Msg.unparsed("portal", portal.name()), Msg.unparsed("area", a.describe()),
                Msg.unparsed("size", String.format(Locale.ROOT, "%dx%dx%d", a.sizeX(), a.sizeY(), a.sizeZ())),
                Msg.unparsed("target", portal.target().describe()),
                Msg.unparsed("permission", portal.permission() == null ? none : portal.permission()),
                Msg.unparsed("action", portal.action() == null ? none : portal.action()),
                Msg.unparsed("creator", creator));
        return Cmds.OK;
    }

    private int setTarget(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        String spec = StringArgumentType.getString(c, "target");
        Optional<PortalTarget> parsed = PortalTarget.parse(spec,
                () -> sender instanceof Player p ? Optional.of(StoredLocation.of(p.getLocation())) : Optional.empty());
        if (parsed.isEmpty()) {
            throw cmds.fail(c.getSource(), "portals.target-invalid");
        }
        PortalTarget target = parsed.get();
        boolean proxyAvailable = ctx.services().get(ProxyService.class).isPresent();
        if (target.type() == TargetType.SERVER && !proxyAvailable) {
            ctx.logger().info("Portal '{}' now targets server '{}' (stored only: the proxy module is not enabled)",
                    name, target.value());
        }
        report(sender, name, registry.update(name, p -> p.withTarget(target)), result -> {
            if (result.outcome() != PortalRegistry.Outcome.UPDATED) {
                return send(sender, "portals.not-found", name);
            }
            boolean storedOnly = target.type() == TargetType.SERVER && !proxyAvailable;
            messages.send(sender, storedOnly ? "portals.target-set-server" : "portals.target-set",
                    Msg.unparsed("portal", result.portal().map(Portal::name).orElse(name)), Msg.unparsed("target", target.describe()));
            return true;
        });
        return Cmds.OK;
    }

    private int setCommand(CommandContext<CommandSourceStack> c, String line) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        String value = line == null || line.isBlank() ? null : line.strip();
        if (value != null) {
            ActionService actions = ctx.services().get(ActionService.class)
                    .orElseThrow(() -> cmds.fail(c.getSource(), "portals.actions-unavailable"));
            List<String> errors = actions.validate(List.of(value));
            if (!errors.isEmpty()) {
                throw cmds.fail(c.getSource(), "portals.command-invalid", Msg.unparsed("error", String.join("; ", errors)));
            }
        }
        report(sender, name, registry.update(name, p -> p.withAction(value)), result -> result.outcome() == PortalRegistry.Outcome.UPDATED
                ? send(sender, value == null ? "portals.command-cleared" : "portals.command-set",
                        result.portal().map(Portal::name).orElse(name))
                : send(sender, "portals.not-found", name));
        return Cmds.OK;
    }

    private int setPermission(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        String node = StringArgumentType.getString(c, "permission");
        String value = node.equalsIgnoreCase("none") ? null : node;
        if (value != null && !Portal.validPermission(value)) {
            throw cmds.fail(c.getSource(), "portals.permission-invalid");
        }
        report(sender, name, registry.update(name, p -> p.withPermission(value)), result -> {
            if (result.outcome() != PortalRegistry.Outcome.UPDATED) {
                return send(sender, "portals.not-found", name);
            }
            messages.send(sender, value == null ? "portals.permission-cleared" : "portals.permission-set",
                    Msg.unparsed("portal", result.portal().map(Portal::name).orElse(name)),
                    Msg.unparsed("permission", value == null ? "" : value));
            return true;
        });
        return Cmds.OK;
    }

    private boolean send(CommandSender sender, String key, String portal, TagResolver... extra) {
        List<TagResolver> tags = new ArrayList<>(List.of(extra));
        tags.add(Msg.unparsed("portal", portal));
        messages.send(sender, key, tags.toArray(TagResolver[]::new));
        return true;
    }

    private void report(CommandSender sender, String name, CompletableFuture<PortalRegistry.Result> future,
            java.util.function.Function<PortalRegistry.Result, Boolean> onResult) {
        future.whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Saving portal '{}' failed", name, error);
                messages.send(sender, "portals.save-failed");
                return;
            }
            try {
                onResult.apply(result);
            } catch (RuntimeException e) {
                ctx.logger().error("Reporting the change of portal '{}' failed", name, e);
            }
        });
    }
}
