package dev.yanianz.atrium.module.inspect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.afk.AfkService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.util.RayTraceResult;

/** Read-only diagnostics for staff. Nothing here mutates the world or a player. */
final class InspectCommands {
    static final String PERM_BLOCKINFO = "atrium.blockinfo";
    static final String PERM_BLOCKNBT = "atrium.blocknbt";
    static final String PERM_ENTITYINFO = "atrium.entityinfo";
    static final String PERM_ENTITYNBT = "atrium.entitynbt";
    static final String PERM_ITEMINFO = "atrium.iteminfo";
    static final String PERM_ITEMNBT = "atrium.itemnbt";
    static final String PERM_CHECKPERM = "atrium.checkperm";
    static final String PERM_CHECKPERM_OTHERS = "atrium.checkperm.others";
    static final String PERM_CHECKCOMMAND = "atrium.checkcommand";
    static final String PERM_CHECKEXP = "atrium.checkexp";
    static final String PERM_CHECKEXP_OTHERS = "atrium.checkexp.others";
    static final String PERM_AFKCHECK = "atrium.afkcheck";
    static final String PERM_AFKCHECK_OTHERS = "atrium.afkcheck.others";
    static final String PERM_LASTONLINE = "atrium.lastonline";
    static final String PERM_OPS = "atrium.ops";
    static final String PERM_CHECKACCOUNT = "atrium.checkaccount";
    static final String PERM_CHECKACCOUNT_OTHERS = "atrium.checkaccount.others";
    static final String PERM_CHECKACCOUNT_IP = "atrium.checkaccount.ip";
    static final String PERM_HASPERMISSION = "atrium.haspermission";
    static final String PERM_HASPERMISSION_OTHERS = "atrium.haspermission.others";
    static final String PERM_PERMREG = "atrium.permreg";

    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<InspectConfig> config;
    private final StatsCommands stats;
    private final AdminCommands admin;
    private volatile DateTimeFormatter dates = InspectFormat.formatter(DEFAULT_DATE_FORMAT, "");

    private record ExpView(int level, float progress) {}

    private record AfkEntry(String name, Instant since) {}

    private record NamedPermission(String name, PermissionResult result) {}

    private record AccountView(String target, String address, boolean showAddress, List<PlayerRef> accounts) {}

    InspectCommands(ModuleContext ctx, ConfigHandle<InspectConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.stats = new StatsCommands(ctx, config);
        this.admin = new AdminCommands(ctx, config);
    }

    private InspectConfig cfg() {
        return config.get();
    }

    /** Rebuilds the date formatter from config; an invalid value falls back to the default and is logged. */
    void reload() {
        InspectConfig value = cfg();
        try {
            dates = InspectFormat.formatter(value.dateFormat, value.timeZone);
        } catch (IllegalArgumentException e) {
            ctx.logger().warn("Invalid dateFormat '{}' or timeZone '{}' in modules/inspect.yml; using {}",
                    value.dateFormat, value.timeZone, DEFAULT_DATE_FORMAT, e);
            dates = InspectFormat.formatter(DEFAULT_DATE_FORMAT, "");
        }
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("blockinfo", PERM_BLOCKINFO)
                .executes(this::blockInfo)
                .build(), "Inspect the block you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("blocknbt", PERM_BLOCKNBT)
                .executes(this::blockNbt)
                .build(), "Print the state and stored data of the block you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("entityinfo", PERM_ENTITYINFO)
                .executes(this::entityInfo)
                .build(), "Inspect the entity you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("entitynbt", PERM_ENTITYNBT)
                .executes(this::entityNbt)
                .build(), "Print the SNBT data of the entity you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("iteminfo", PERM_ITEMINFO)
                .executes(this::itemInfo)
                .build(), "Inspect the item in your main hand"));
        ctx.command(CommandSpec.of(Cmds.literal("itemnbt", PERM_ITEMNBT)
                .executes(this::itemNbt)
                .build(), "Print the data components of the item in your main hand"));
        ctx.command(CommandSpec.of(Cmds.literal("checkperm", PERM_CHECKPERM)
                .executes(c -> usage(c, "inspect.usage.checkperm"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .then(Commands.argument("node", StringArgumentType.word())
                                .suggests(permissionSuggestions())
                                .executes(this::checkPerm)))
                .build(), "Check whether a player has a permission node, online or offline"));
        ctx.command(CommandSpec.of(Cmds.literal("checkcommand", PERM_CHECKCOMMAND)
                .executes(c -> usage(c, "inspect.usage.checkcommand"))
                .then(Commands.argument("command", StringArgumentType.word())
                        .suggests(Cmds.suggest(this::commandNames))
                        .executes(this::checkCommand))
                .build(), "Show the plugin, aliases and permission of a command"));
        ctx.command(CommandSpec.of(Cmds.literal("checkexp", PERM_CHECKEXP)
                .executes(this::checkExpSelf)
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::checkExp))
                .build(), "Show a player's level, progress and total experience"));
        ctx.command(CommandSpec.of(Cmds.literal("afkcheck", PERM_AFKCHECK)
                .executes(c -> afkList(c, 1))
                .then(Cmds.literal("all")
                        .executes(c -> afkList(c, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .suggests(pageSuggestions())
                                .executes(c -> afkList(c, IntegerArgumentType.getInteger(c, "page")))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::afkSingle))
                .build(), "List AFK players and how long they have been AFK"));
        ctx.command(CommandSpec.of(Cmds.literal("lastonline", PERM_LASTONLINE)
                .executes(c -> lastOnline(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .suggests(pageSuggestions())
                        .executes(c -> lastOnline(c, IntegerArgumentType.getInteger(c, "page"))))
                .build(), "List the most recently seen players"));
        ctx.command(CommandSpec.of(Cmds.literal("ops", PERM_OPS)
                .executes(c -> ops(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .suggests(pageSuggestions())
                        .executes(c -> ops(c, IntegerArgumentType.getInteger(c, "page"))))
                .build(), "List server operators"));
        ctx.command(CommandSpec.of(Cmds.literal("checkaccount", PERM_CHECKACCOUNT)
                .executes(c -> usage(c, "inspect.usage.checkaccount"))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> checkAccount(c, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .suggests(pageSuggestions())
                                .executes(c -> checkAccount(c, IntegerArgumentType.getInteger(c, "page")))))
                .build(), "Show the accounts of a player or an IP address"));
        ctx.command(CommandSpec.of(Cmds.literal("haspermission", PERM_HASPERMISSION)
                .executes(c -> usage(c, "inspect.usage.haspermission"))
                .then(Commands.argument("nodeOrPlayer", StringArgumentType.word())
                        .suggests(permissionOrPlayerSuggestions())
                        .executes(this::hasPermissionSelf)
                        .then(Commands.argument("node", StringArgumentType.word())
                                .suggests(permissionSuggestions())
                                .executes(this::hasPermissionOther)))
                .build(), "Show whether you or another player have a permission node"));
        ctx.command(CommandSpec.of(Cmds.literal("permreg", PERM_PERMREG)
                .executes(c -> permissionRegistry(c, null, 1))
                .then(Commands.argument("filter", StringArgumentType.word())
                        .suggests(permissionSuggestions())
                        .executes(c -> permissionRegistry(c, StringArgumentType.getString(c, "filter"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .suggests(pageSuggestions())
                                .executes(c -> permissionRegistry(c, StringArgumentType.getString(c, "filter"),
                                        IntegerArgumentType.getInteger(c, "page")))))
                .build(), "List registered permission nodes"));
        stats.register();
        admin.register();
    }

    // ---- blocks ---------------------------------------------------------------------------------

    private int blockInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Block block = targetBlock(player);
        if (block == null) {
            throw cmds.fail(c.getSource(), "inspect.no-block", Msg.number("distance", cfg().blockDistance()));
        }
        CommandSender sender = c.getSource().getSender();
        Location location = block.getLocation();
        ctx.scheduler().supplyAt(location, () -> Snapshots.block(location.getBlock())).whenComplete((snapshot, error) -> {
            if (error != null) {
                report(sender, "Reading block info", error);
            } else {
                onSender(sender, () -> sendBlock(sender, snapshot));
            }
        });
        return Cmds.OK;
    }

    private int blockNbt(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Block block = targetBlock(player);
        if (block == null) {
            throw cmds.fail(c.getSource(), "inspect.no-block", Msg.number("distance", cfg().blockDistance()));
        }
        CommandSender sender = c.getSource().getSender();
        Location location = block.getLocation();
        ctx.scheduler().supplyAt(location, () -> Snapshots.block(location.getBlock())).whenComplete((snapshot, error) -> {
            if (error != null) {
                report(sender, "Reading block data", error);
            } else {
                onSender(sender, () -> sendBlockDump(sender, snapshot));
            }
        });
        return Cmds.OK;
    }

    private Block targetBlock(Player player) {
        int distance = cfg().blockDistance();
        RayTraceResult result = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER);
        Block block = result == null ? null : result.getHitBlock();
        if (block == null) {
            block = player.getTargetBlockExact(distance);
        }
        return block == null || block.getType().isAir() ? null : block;
    }

    private void sendBlock(CommandSender sender, Snapshots.BlockSnapshot snapshot) {
        ctx.messages().send(sender, "inspect.block-header",
                Msg.unparsed("world", snapshot.world()), Msg.number("x", snapshot.x()),
                Msg.number("y", snapshot.y()), Msg.number("z", snapshot.z()));
        ctx.messages().send(sender, "inspect.block-type", Msg.unparsed("type", snapshot.material()));
        ctx.messages().send(sender, "inspect.block-data", Msg.unparsed("data", snapshot.data()));
        ctx.messages().send(sender, "inspect.block-light", Msg.number("block", snapshot.blockLight()),
                Msg.number("sky", snapshot.skyLight()));
        ctx.messages().send(sender, "inspect.block-biome",
                Msg.unparsed("biome", InspectFormat.shortKey(snapshot.biome())));
        ctx.messages().send(sender, "inspect.block-tile",
                Msg.component("tile", snapshot.tileEntity().isEmpty()
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(snapshot.tileEntity())));
        ctx.messages().send(sender, "inspect.block-hardness",
                Msg.unparsed("hardness", InspectFormat.number(snapshot.hardness())),
                Msg.unparsed("blast", InspectFormat.number(snapshot.blastResistance())));
        ctx.messages().send(sender, "inspect.block-hitbox", Msg.unparsed("box", snapshot.hitbox()));
        ctx.messages().send(sender, "inspect.block-environment",
                Msg.unparsed("temperature", InspectFormat.number(snapshot.temperature())),
                Msg.unparsed("humidity", InspectFormat.number(snapshot.humidity())));
        if (!snapshot.note().isEmpty()) {
            ctx.messages().send(sender, "inspect.block-note", Msg.unparsed("note", snapshot.note()));
        }
        ctx.messages().send(sender, "inspect.block-contents",
                Msg.component("value", snapshot.contents().isEmpty()
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(String.join(" | ", snapshot.contents()))));
        ctx.messages().send(sender, "inspect.block-thread", Msg.unparsed("thread", snapshot.thread()));
    }

    private void sendBlockDump(CommandSender sender, Snapshots.BlockSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add("location: " + snapshot.world() + " " + snapshot.x() + " " + snapshot.y() + " " + snapshot.z());
        lines.add("type: " + snapshot.material());
        lines.add("data: " + snapshot.data());
        lines.add("biome: " + snapshot.biome());
        lines.add("light: block=" + snapshot.blockLight() + ", sky=" + snapshot.skyLight());
        lines.add("hardness: " + snapshot.hardness() + ", blast-resistance: " + snapshot.blastResistance());
        lines.add("hitbox: " + snapshot.hitbox());
        lines.add("temperature: " + snapshot.temperature() + ", humidity: " + snapshot.humidity());
        if (!snapshot.note().isEmpty()) {
            lines.add("note: " + snapshot.note());
        }
        if (!snapshot.contents().isEmpty()) {
            lines.add("contents: " + String.join(", ", snapshot.contents()));
        }
        lines.add("tile: " + (snapshot.tileEntity().isEmpty() ? "none" : snapshot.tileEntity()));
        lines.add("thread: " + snapshot.thread());
        lines.addAll(snapshot.tileData());
        sendDump(sender, "blocknbt", String.join("\n", lines));
    }

    // ---- entities -------------------------------------------------------------------------------

    private int entityInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Entity entity = lookedAtEntity(c);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(entity, () -> Snapshots.entity(entity)).whenComplete((snapshot, error) -> {
            if (error != null) {
                report(sender, "Reading entity info", error);
            } else {
                onSender(sender, () -> sendEntity(sender, snapshot));
            }
        });
        return Cmds.OK;
    }

    private int entityNbt(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Entity entity = lookedAtEntity(c);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(entity, () -> Snapshots.entity(entity)).whenComplete((snapshot, error) -> {
            if (error != null) {
                report(sender, "Reading entity data", error);
            } else {
                onSender(sender, () -> sendDump(sender, "entitynbt", snapshot.snbt()));
            }
        });
        return Cmds.OK;
    }

    private Entity lookedAtEntity(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        RayTraceResult result = player.rayTraceEntities(cfg().entityDistance());
        Entity entity = result == null ? null : result.getHitEntity();
        if (entity == null) {
            throw cmds.fail(c.getSource(), "inspect.no-entity", Msg.number("distance", cfg().entityDistance()));
        }
        return entity;
    }

    private void sendEntity(CommandSender sender, Snapshots.EntitySnapshot snapshot) {
        ctx.messages().send(sender, "inspect.entity-header",
                Msg.unparsed("type", InspectFormat.shortKey(snapshot.type())),
                Msg.unparsed("uuid", snapshot.uuid().toString()));
        ctx.messages().send(sender, "inspect.entity-name",
                Msg.component("name", snapshot.hasCustomName()
                        ? Component.text(snapshot.customName())
                        : ctx.messages().render(sender, "inspect.none")));
        if (snapshot.living()) {
            ctx.messages().send(sender, "inspect.entity-health",
                    Msg.unparsed("health", InspectFormat.number(snapshot.health())),
                    Msg.unparsed("max", snapshot.maxHealth() < 0 ? "?" : InspectFormat.number(snapshot.maxHealth())));
        } else {
            ctx.messages().send(sender, "inspect.entity-health-na");
        }
        ctx.messages().send(sender, "inspect.entity-age", Msg.number("age", snapshot.ageTicks()));
        ctx.messages().send(sender, "inspect.entity-persistent",
                Msg.component("value", yesNo(sender, snapshot.persistent())));
        ctx.messages().send(sender, "inspect.entity-passengers",
                Msg.component("value", snapshot.passengers().isEmpty()
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(String.join(" | ", snapshot.passengers()))));
        ctx.messages().send(sender, "inspect.entity-vehicle",
                Msg.component("value", snapshot.vehicle().isEmpty()
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(snapshot.vehicle())));
        ctx.messages().send(sender, "inspect.entity-owner",
                Msg.component("value", snapshot.owner().isEmpty()
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(snapshot.owner())));
        for (Snapshots.Detail detail : snapshot.details()) {
            ctx.messages().send(sender, "inspect.entity-detail",
                    Msg.unparsed("key", InspectFormat.prettyKey(detail.key())),
                    Msg.unparsed("value", detail.value()));
        }
    }

    // ---- items ----------------------------------------------------------------------------------

    private int itemInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (player.getInventory().getItemInMainHand().isEmpty()) {
            throw cmds.fail(c.getSource(), "inspect.no-item");
        }
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(player,
                        () -> Snapshots.item(player.getInventory().getItemInMainHand()))
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        report(sender, "Reading item info", error);
                    } else {
                        onSender(sender, () -> sendItem(sender, snapshot));
                    }
                });
        return Cmds.OK;
    }

    private int itemNbt(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (player.getInventory().getItemInMainHand().isEmpty()) {
            throw cmds.fail(c.getSource(), "inspect.no-item");
        }
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(player,
                        () -> Snapshots.item(player.getInventory().getItemInMainHand()))
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        report(sender, "Reading item data", error);
                    } else {
                        onSender(sender, () -> sendDump(sender, "itemnbt", snapshot.snbt()));
                    }
                });
        return Cmds.OK;
    }

    private void sendItem(CommandSender sender, Snapshots.ItemSnapshot snapshot) {
        ctx.messages().send(sender, "inspect.item-header",
                Msg.unparsed("type", InspectFormat.shortKey(snapshot.id())), Msg.number("amount", snapshot.amount()));
        if (snapshot.customName()) {
            ctx.messages().send(sender, "inspect.item-name", Msg.unparsed("name", snapshot.displayName()));
        }
        ctx.messages().send(sender, "inspect.item-components",
                Msg.number("count", snapshot.components().size()));
        int shown = 0;
        for (Snapshots.ItemComponent component : snapshot.components()) {
            if (shown >= cfg().inlineComponents()) {
                break;
            }
            ctx.messages().send(sender, "inspect.item-component",
                    Msg.unparsed("key", InspectFormat.shortKey(component.key())),
                    Msg.unparsed("value", InspectFormat.truncate(component.value(), 256)));
            shown++;
        }
        if (snapshot.components().size() > shown) {
            ctx.messages().send(sender, "inspect.item-components-more",
                    Msg.number("shown", shown), Msg.number("total", snapshot.components().size()));
        }
        ctx.messages().send(sender, "inspect.item-copy", Msg.component("copy", copyButton(sender, snapshot.snbt())));
    }

    private Component copyButton(CommandSender sender, String dump) {
        String limited = InspectFormat.truncate(dump, cfg().dumpLength());
        String hover = InspectFormat.truncate(limited, 2000);
        return ctx.messages().render(sender, "inspect.copy")
                .clickEvent(ClickEvent.copyToClipboard(limited))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    // ---- permissions ----------------------------------------------------------------------------

    private int checkPerm(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String node = StringArgumentType.getString(c, "node");
        if (visiblePlayer(sender, name) == null && !LuckPermsHook.available()) {
            ctx.messages().send(sender, "inspect.checkperm-online-only");
            return Cmds.OK;
        }
        resolvePermission(c.getSource(), sender, name, node, PERM_CHECKPERM_OTHERS).whenComplete((result, error) -> {
            if (error != null) {
                report(sender, "Checking permission " + node, error);
                return;
            }
            onSender(sender, () -> {
                if (result.isEmpty()) {
                    ctx.messages().send(sender, "core.user-not-found", Msg.unparsed("name", name));
                } else {
                    sendPermission(sender, node, result.get());
                }
            });
        });
        return Cmds.OK;
    }

    private int hasPermissionSelf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player player = cmds.player(c.getSource());
        String node = StringArgumentType.getString(c, "nodeOrPlayer");
        sendPermission(sender, node, new NamedPermission(player.getName(), onlinePermission(player, node)));
        return Cmds.OK;
    }

    private int hasPermissionOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "nodeOrPlayer");
        String node = StringArgumentType.getString(c, "node");
        if (visiblePlayer(sender, name) == null && !LuckPermsHook.available()) {
            ctx.messages().send(sender, "inspect.checkperm-online-only");
            return Cmds.OK;
        }
        resolvePermission(c.getSource(), sender, name, node, PERM_HASPERMISSION_OTHERS)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        report(sender, "Checking permission " + node, error);
                        return;
                    }
                    onSender(sender, () -> {
                        if (result.isEmpty()) {
                            ctx.messages().send(sender, "core.user-not-found", Msg.unparsed("name", name));
                        } else {
                            sendPermission(sender, node, result.get());
                        }
                    });
                });
        return Cmds.OK;
    }

    private CompletableFuture<Optional<NamedPermission>> resolvePermission(CommandSourceStack source,
            CommandSender sender, String name, String node, String othersPermission) throws CommandSyntaxException {
        boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(name);
        if (!self && !sender.hasPermission(othersPermission)) {
            throw cmds.fail(source, "core.no-permission-others", Msg.unparsed("permission", othersPermission));
        }
        Player online = visiblePlayer(sender, name);
        if (online != null) {
            return ctx.scheduler().supplyOnEntity(online,
                    () -> Optional.of(new NamedPermission(online.getName(), onlinePermission(online, node))));
        }
        if (!LuckPermsHook.available()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return ctx.users().resolveAccount(name, false).thenCompose(reference -> {
            if (reference.isEmpty()) {
                return LuckPermsHook.lookupUniqueId(name).thenCompose(uuid -> uuid.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<NamedPermission>empty())
                        : checkOffline(uuid.get(), name, node));
            }
            String display = reference.get().name();
            return checkOffline(reference.get().uuid(), display, node);
        });
    }

    private CompletableFuture<Optional<NamedPermission>> checkOffline(UUID uuid, String display, String node) {
        CompletableFuture<PermissionResult> check;
        try {
            check = LuckPermsHook.check(uuid, node);
        } catch (RuntimeException | LinkageError e) {
            check = CompletableFuture.failedFuture(e);
        }
        return check.thenApply(result -> Optional.of(new NamedPermission(display, result)));
    }

    /** Must run on the player's own thread. */
    private static PermissionResult onlinePermission(Player player, String node) {
        List<String> matches = player.getEffectivePermissions().stream()
                .filter(info -> info.getPermission().equalsIgnoreCase(node))
                .sorted(Comparator.comparing(info -> info.getPermission().toLowerCase(Locale.ROOT)))
                .limit(5)
                .map(info -> (info.getValue() ? "+" : "-") + info.getPermission())
                .toList();
        return new PermissionResult(player.hasPermission(node), player.isPermissionSet(node), "Bukkit (online)",
                matches);
    }

    private void sendPermission(CommandSender sender, String node, NamedPermission named) {
        PermissionResult result = named.result();
        ctx.messages().send(sender, "inspect.checkperm-header",
                Msg.unparsed("player", named.name()), Msg.unparsed("node", node));
        ctx.messages().send(sender, "inspect.checkperm-value",
                Msg.component("value", yesNo(sender, result.value())));
        ctx.messages().send(sender, "inspect.checkperm-explicit",
                Msg.component("value", yesNo(sender, result.explicit())));
        if (!result.source().isEmpty()) {
            ctx.messages().send(sender, "inspect.checkperm-source", Msg.unparsed("source", result.source()));
        }
        if (!result.details().isEmpty()) {
            ctx.messages().send(sender, "inspect.checkperm-details",
                    Msg.unparsed("details", String.join(", ", result.details())));
        }
        Permission registered = Bukkit.getPluginManager().getPermission(node);
        if (registered == null) {
            ctx.messages().send(sender, "inspect.checkperm-default-none");
        } else {
            ctx.messages().send(sender, "inspect.checkperm-default",
                    Msg.unparsed("default", registered.getDefault().name().toLowerCase(Locale.ROOT)),
                    Msg.unparsed("description", InspectFormat.truncate(
                            registered.getDescription() == null ? "" : registered.getDescription(), 256)));
        }
    }

    // ---- commands -------------------------------------------------------------------------------

    private int checkCommand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "command").toLowerCase(Locale.ROOT);
        CommandMap map = Bukkit.getCommandMap();
        Command command = map.getKnownCommands().get(name);
        if (command == null) {
            throw cmds.fail(c.getSource(), "inspect.checkcommand-unknown", Msg.unparsed("command", name));
        }
        Set<String> aliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var entry : map.getKnownCommands().entrySet()) {
            if (entry.getValue() == command) {
                aliases.add(entry.getKey());
            }
        }
        aliases.addAll(command.getAliases());
        aliases.removeIf(alias -> alias.equalsIgnoreCase(command.getName()));
        String owner = command instanceof PluginIdentifiableCommand identifiable
                ? identifiable.getPlugin().getName()
                : "";
        ctx.messages().send(sender, "inspect.checkcommand-header", Msg.unparsed("command", name));
        ctx.messages().send(sender, "inspect.checkcommand-canonical",
                Msg.unparsed("name", command.getName()));
        ctx.messages().send(sender, "inspect.checkcommand-owner",
                Msg.component("owner", owner.isEmpty()
                        ? ctx.messages().render(sender, "inspect.checkcommand-server")
                        : Component.text(owner)));
        ctx.messages().send(sender, "inspect.checkcommand-aliases",
                Msg.component("aliases", aliases.isEmpty()
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(String.join(", ", aliases))));
        ctx.messages().send(sender, "inspect.checkcommand-permission",
                Msg.component("permission", command.getPermission() == null
                        ? ctx.messages().render(sender, "inspect.none")
                        : Component.text(command.getPermission())));
        ctx.messages().send(sender, "inspect.checkcommand-description",
                Msg.unparsed("description", InspectFormat.truncate(
                        command.getDescription() == null ? "" : command.getDescription(), 256)));
        ctx.messages().send(sender, "inspect.checkcommand-usage",
                Msg.unparsed("usage", InspectFormat.truncate(
                        command.getUsage() == null ? "" : command.getUsage(), 256)));
        return Cmds.OK;
    }

    private List<String> commandNames() {
        return Bukkit.getCommandMap().getKnownCommands().keySet().stream().sorted().limit(2000).toList();
    }

    // ---- experience -----------------------------------------------------------------------------

    private int checkExpSelf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return checkExp(c, cmds.player(c.getSource()));
    }

    private int checkExp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "target");
        Player target = visiblePlayer(sender, name);
        if (target == null) {
            ctx.messages().send(sender, "inspect.checkexp-offline", Msg.unparsed("player", name));
            return Cmds.OK;
        }
        return checkExp(c, target);
    }

    private int checkExp(CommandContext<CommandSourceStack> c, Player target) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!self && !sender.hasPermission(PERM_CHECKEXP_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission",
                    PERM_CHECKEXP_OTHERS));
        }
        ctx.scheduler().supplyOnEntity(target, () -> new ExpView(target.getLevel(), target.getExp()))
                .whenComplete((view, error) -> {
                    if (error != null) {
                        report(sender, "Reading experience of " + target.getName(), error);
                        return;
                    }
                    onSender(sender, () -> sendExp(sender, target.getName(), view));
                });
        return Cmds.OK;
    }

    private void sendExp(CommandSender sender, String player, ExpView view) {
        long next = ExpMath.expToNextLevel(view.level());
        long inside = Math.min(next, (long) Math.floor(next * Math.max(0, Math.min(1, view.progress()))));
        long total = ExpMath.totalExp(view.level(), view.progress());
        ctx.messages().send(sender, "inspect.checkexp-header", Msg.unparsed("player", player));
        ctx.messages().send(sender, "inspect.checkexp-level", Msg.number("level", view.level()));
        ctx.messages().send(sender, "inspect.checkexp-progress",
                Msg.unparsed("percent", InspectFormat.percent(view.progress())),
                Msg.number("current", inside), Msg.number("next", next),
                Msg.number("next_level", view.level() + 1));
        ctx.messages().send(sender, "inspect.checkexp-total", Msg.number("total", total));
        ctx.messages().send(sender, "inspect.checkexp-to-next", Msg.number("remaining", Math.max(0, next - inside)));
    }

    // ---- AFK ------------------------------------------------------------------------------------

    private int afkList(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        Optional<AfkService> service = ctx.services().get(AfkService.class);
        if (service.isEmpty()) {
            ctx.messages().send(sender, "inspect.afkcheck-unsupported");
            return Cmds.OK;
        }
        Optional<VanishService> vanish = ctx.services().get(VanishService.class);
        List<AfkEntry> entries = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!canSee(sender, player, vanish)) {
                continue;
            }
            service.get().afkSince(player.getUniqueId())
                    .ifPresent(since -> entries.add(new AfkEntry(player.getName(), since)));
        }
        entries.sort(Comparator.comparing(AfkEntry::since));
        List<AfkEntry> shown = Pages.slice(entries, page, cfg().entriesPerPage());
        int pages = Pages.pageCount(entries.size(), cfg().entriesPerPage());
        int current = Pages.clamp(page, pages);
        if (entries.isEmpty()) {
            ctx.messages().send(sender, "inspect.afkcheck-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "inspect.afkcheck-header", Msg.number("count", entries.size()));
        Instant now = Instant.now();
        for (AfkEntry entry : shown) {
            ctx.messages().send(sender, "inspect.afkcheck-entry",
                    Msg.unparsed("player", entry.name()),
                    Msg.duration("time", InspectFormat.since(entry.since(), now)));
        }
        pageFooter(sender, current, pages, entries.size());
        return Cmds.OK;
    }

    private int afkSingle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        Optional<AfkService> service = ctx.services().get(AfkService.class);
        if (service.isEmpty()) {
            ctx.messages().send(sender, "inspect.afkcheck-unsupported");
            return Cmds.OK;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            throw cmds.fail(c.getSource(), "core.player-not-found");
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!self && !sender.hasPermission(PERM_AFKCHECK_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission",
                    PERM_AFKCHECK_OTHERS));
        }
        Optional<VanishService> vanish = ctx.services().get(VanishService.class);
        if (!canSee(sender, target, vanish)) {
            throw cmds.fail(c.getSource(), "core.player-not-found");
        }
        Optional<Instant> since = service.get().afkSince(target.getUniqueId());
        if (since.isEmpty()) {
            ctx.messages().send(sender, "inspect.afkcheck-single-not", Msg.unparsed("player", target.getName()));
        } else {
            ctx.messages().send(sender, "inspect.afkcheck-single-afk", Msg.unparsed("player", target.getName()),
                    Msg.duration("time", InspectFormat.since(since.get(), Instant.now())));
        }
        return Cmds.OK;
    }

    // ---- last online ----------------------------------------------------------------------------

    private int lastOnline(CommandContext<CommandSourceStack> c, int requestedPage) {
        CommandSender sender = c.getSource().getSender();
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(sender, player, ctx.services().get(VanishService.class))) {
                online.add(player.getUniqueId());
            }
        }
        int pageSize = cfg().entriesPerPage();
        String table = ctx.database().table("users");
        ctx.database().query(connection -> {
            int total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                    ResultSet rows = count.executeQuery()) {
                total = rows.next() ? rows.getInt(1) : 0;
            }
            int pages = Pages.pageCount(total, pageSize);
            int page = Pages.clamp(requestedPage, pages);
            StringBuilder sql = new StringBuilder("SELECT uuid, name, last_seen FROM ").append(table);
            List<Object> parameters = new ArrayList<>();
            if (!online.isEmpty()) {
                sql.append(" ORDER BY CASE WHEN uuid IN (");
                int index = 0;
                for (UUID uuid : online) {
                    if (index++ > 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                    parameters.add(uuid.toString());
                }
                sql.append(") THEN 1 ELSE 0 END DESC, last_seen DESC");
            } else {
                sql.append(" ORDER BY last_seen DESC");
            }
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(pageSize);
            parameters.add(Pages.offset(page, pageSize));
            List<Snapshots.LastSeenRow> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID uuid = UUID.fromString(rows.getString(1));
                        result.add(new Snapshots.LastSeenRow(uuid, rows.getString(2), rows.getLong(3),
                                online.contains(uuid)));
                    }
                }
            }
            return new Snapshots.LastOnlinePage(total, page, pages, List.copyOf(result));
        }).whenComplete((result, error) -> {
            if (error != null) {
                report(sender, "Reading the last seen list", error);
                return;
            }
            onSender(sender, () -> sendLastOnline(sender, result));
        });
        return Cmds.OK;
    }

    private void sendLastOnline(CommandSender sender, Snapshots.LastOnlinePage page) {
        if (page.total() == 0) {
            ctx.messages().send(sender, "inspect.lastonline-empty");
            return;
        }
        Instant now = Instant.now();
        ctx.messages().send(sender, "inspect.lastonline-header", Msg.number("count", page.total()));
        for (Snapshots.LastSeenRow row : page.rows()) {
            Instant seen = row.online() ? now : Instant.ofEpochMilli(row.lastSeen());
            ctx.messages().send(sender, "inspect.lastonline-entry",
                    Msg.unparsed("player", row.name()),
                    Msg.component("status", ctx.messages().render(sender,
                            row.online() ? "inspect.online" : "inspect.offline")),
                    Msg.duration("ago", InspectFormat.since(seen, now)),
                    Msg.unparsed("date", InspectFormat.absolute(seen, dates)));
        }
        pageFooter(sender, page.page(), page.pages(), page.total());
    }

    // ---- operators ------------------------------------------------------------------------------

    private int ops(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        List<OfflinePlayer> operators = new ArrayList<>(Bukkit.getOperators());
        operators.sort(Comparator.comparing(InspectCommands::nameOf, String.CASE_INSENSITIVE_ORDER));
        if (operators.isEmpty()) {
            ctx.messages().send(sender, "inspect.ops-empty");
            return Cmds.OK;
        }
        List<OfflinePlayer> shown = Pages.slice(operators, page, cfg().entriesPerPage());
        int pages = Pages.pageCount(operators.size(), cfg().entriesPerPage());
        int current = Pages.clamp(page, pages);
        ctx.messages().send(sender, "inspect.ops-header", Msg.number("count", operators.size()));
        for (OfflinePlayer operator : shown) {
            ctx.messages().send(sender, "inspect.ops-entry",
                    Msg.unparsed("player", nameOf(operator)),
                    Msg.component("status", ctx.messages().render(sender,
                            onlineAndVisible(sender, operator.getUniqueId())
                                    ? "inspect.online" : "inspect.offline")));
        }
        pageFooter(sender, current, pages, operators.size());
        return Cmds.OK;
    }

    private static String nameOf(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    // ---- accounts -------------------------------------------------------------------------------

    private int checkAccount(CommandContext<CommandSourceStack> c, int page) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String target = StringArgumentType.getString(c, "target");
        boolean address = InspectFormat.isAddress(target);
        if (address && !sender.hasPermission(PERM_CHECKACCOUNT_IP)) {
            throw cmds.fail(c.getSource(), "inspect.checkaccount-ip-denied",
                    Msg.unparsed("permission", PERM_CHECKACCOUNT_IP));
        }
        boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(target);
        if (!self && !sender.hasPermission(PERM_CHECKACCOUNT_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission",
                    PERM_CHECKACCOUNT_OTHERS));
        }
        boolean showAddress = sender.hasPermission(PERM_CHECKACCOUNT_IP);
        if (address) {
            ctx.users().accountsByAddress(target).whenComplete((accounts, error) -> {
                if (error != null) {
                    report(sender, "Checking address " + target, error);
                } else {
                    onSender(sender, () -> sendAccounts(sender, new AccountView(target, target, true, accounts), page));
                }
            });
            return Cmds.OK;
        }
        ctx.users().resolveAccount(target, false)
                .thenCompose(reference -> reference.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<AtriumUser>empty())
                        : ctx.users().load(reference.get().uuid()))
                .whenComplete((user, error) -> {
                    if (error != null) {
                        report(sender, "Checking account " + target, error);
                        return;
                    }
                    if (user.isEmpty()) {
                        onSender(sender, () -> ctx.messages().send(sender, "core.user-not-found",
                                Msg.unparsed("name", target)));
                        return;
                    }
                    AtriumUser loaded = user.get();
                    Optional<String> lastAddress = loaded.lastAddress();
                    if (lastAddress.isEmpty()) {
                        onSender(sender, () -> ctx.messages().send(sender, "inspect.checkaccount-no-address",
                                Msg.unparsed("player", loaded.name())));
                        return;
                    }
                    ctx.users().accountsByAddress(lastAddress.get()).whenComplete((accounts, failure) -> {
                        if (failure != null) {
                            report(sender, "Checking account " + target, failure);
                        } else {
                            onSender(sender, () -> sendAccounts(sender,
                                    new AccountView(loaded.name(), lastAddress.get(), showAddress, accounts), page));
                        }
                    });
                });
        return Cmds.OK;
    }

    private void sendAccounts(CommandSender sender, AccountView view, int page) {
        List<PlayerRef> accounts = view.accounts();
        if (accounts.isEmpty()) {
            ctx.messages().send(sender, "inspect.checkaccount-empty", Msg.unparsed("target", view.target()));
            return;
        }
        List<PlayerRef> shown = Pages.slice(accounts, page, cfg().entriesPerPage());
        int pages = Pages.pageCount(accounts.size(), cfg().entriesPerPage());
        int current = Pages.clamp(page, pages);
        ctx.messages().send(sender, "inspect.checkaccount-header",
                Msg.unparsed("target", view.target()), Msg.number("count", accounts.size()));
        if (view.showAddress()) {
            ctx.messages().send(sender, "inspect.checkaccount-address",
                    Msg.unparsed("address", view.address()));
        } else {
            ctx.messages().send(sender, "inspect.checkaccount-hidden");
        }
        for (PlayerRef account : shown) {
            ctx.messages().send(sender, "inspect.checkaccount-entry",
                    Msg.unparsed("player", account.name()),
                    Msg.component("status", ctx.messages().render(sender,
                            onlineAndVisible(sender, account.uuid()) ? "inspect.online" : "inspect.offline")));
        }
        pageFooter(sender, current, pages, accounts.size());
    }

    // ---- permission registry --------------------------------------------------------------------

    private int permissionRegistry(CommandContext<CommandSourceStack> c, String filter, int page) {
        CommandSender sender = c.getSource().getSender();
        String needle = InspectFormat.filter(filter);
        List<Permission> permissions = Bukkit.getPluginManager().getPermissions().stream()
                .filter(permission -> needle.isEmpty()
                        || permission.getName().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Permission::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (permissions.isEmpty()) {
            ctx.messages().send(sender, "inspect.permreg-empty", Msg.unparsed("filter",
                    needle.isEmpty() ? "*" : filter));
            return Cmds.OK;
        }
        List<Permission> shown = Pages.slice(permissions, page, cfg().entriesPerPage());
        int pages = Pages.pageCount(permissions.size(), cfg().entriesPerPage());
        int current = Pages.clamp(page, pages);
        ctx.messages().send(sender, "inspect.permreg-header",
                Msg.number("count", permissions.size()),
                Msg.unparsed("filter", needle.isEmpty() ? "*" : filter));
        for (Permission permission : shown) {
            ctx.messages().send(sender, "inspect.permreg-entry",
                    Msg.unparsed("node", permission.getName()),
                    Msg.unparsed("default", permission.getDefault().name().toLowerCase(Locale.ROOT)),
                    Msg.unparsed("description", InspectFormat.truncate(
                            permission.getDescription() == null ? "" : permission.getDescription(), 160)));
        }
        pageFooter(sender, current, pages, permissions.size());
        return Cmds.OK;
    }

    private List<String> permissionNames() {
        return Bukkit.getPluginManager().getPermissions().stream()
                .map(Permission::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(1000)
                .toList();
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> permissionSuggestions() {
        return Cmds.suggest(this::permissionNames);
    }

    /** First /haspermission argument: a node, or a player name when a node follows. */
    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> permissionOrPlayerSuggestions() {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String name : permissionNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(player.getName());
                }
            }
            return builder.buildFuture();
        };
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> pageSuggestions() {
        return Cmds.suggest(() -> List.of("1", "2", "3", "4", "5"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    private void sendDump(CommandSender sender, String label, String dump) {
        String limited = InspectFormat.truncate(dump == null ? "" : dump, cfg().dumpLength());
        ctx.messages().send(sender, "inspect.dump-header",
                Msg.unparsed("label", label), Msg.number("length", limited.length()));
        ctx.messages().send(sender, "inspect.dump-copy", Msg.component("copy", copyButton(sender, limited)));
        String[] lines = limited.split("\n", -1);
        int shown = Math.min(lines.length, cfg().dumpLines());
        for (int i = 0; i < shown; i++) {
            ctx.messages().send(sender, "inspect.dump-line",
                    Msg.unparsed("line", InspectFormat.truncate(lines[i], 256)));
        }
        if (lines.length > shown) {
            ctx.messages().send(sender, "inspect.dump-truncated",
                    Msg.number("shown", shown), Msg.number("total", lines.length));
        }
    }

    private void pageFooter(CommandSender sender, int page, int pages, int total) {
        ctx.messages().send(sender, "inspect.page-footer",
                Msg.number("page", page), Msg.number("pages", pages), Msg.number("total", total));
    }

    private Component yesNo(CommandSender sender, boolean value) {
        return ctx.messages().render(sender, value ? "inspect.allowed" : "inspect.denied");
    }

    private static boolean canSee(CommandSender sender, Player target, Optional<VanishService> vanish) {
        return !(sender instanceof Player viewer) || vanish.map(service -> service.canSee(viewer, target)).orElse(true);
    }

    /**
     * {@link Bukkit#getPlayerExact(String)} that hides a vanished player from a viewer who may not see them,
     * so /checkexp, /checkperm and /checkaccount take the offline path instead of revealing a secret join.
     */
    private Player visiblePlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            return null;
        }
        return canSee(sender, target, ctx.services().get(VanishService.class)) ? target : null;
    }

    /** Whether this account is online <em>and</em> visible to the sender. */
    private boolean onlineAndVisible(CommandSender sender, UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && canSee(sender, player, ctx.services().get(VanishService.class));
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void report(CommandSender sender, String action, Throwable error) {
        ctx.logger().warn("{} failed for {}", action, sender.getName(), error);
        onSender(sender, () -> ctx.messages().send(sender, "core.error"));
    }
}
