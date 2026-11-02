package dev.yanianz.atrium.module.armorstand;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.armorstand.ArmorStandEditEvent.Action;
import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/** /astand: chest GUI editor, named poses and a pose/flag clipboard for the armor stand the player looks at. */
public final class ArmorStandModule implements AtriumModule, Listener {
    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<ArmorStandConfig> config;
    private StandAccess access;
    private StandEditor editor;
    private PoseStore store;
    private TemplateStore templates;
    private TemplateListener templateListener;
    private final Map<UUID, Clipboard> clipboard = new ConcurrentHashMap<>();

    /** Pose and flags only: copying equipment would mint items. */
    private record Clipboard(Pose pose, Set<Toggle> toggles) {}

    @Override
    public String id() {
        return "armorstand";
    }

    @Override
    public String description() {
        return "Armor stand editor with poses, toggles and saved poses";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("integrations");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(ArmorStandConfig.class, ArmorStandConfig::new);
        ctx.migrate(PoseStore.MIGRATIONS);
        this.store = new PoseStore(ctx.database());
        java.nio.file.Path templateFile = ctx.plugin().getDataPath().resolve("modules").resolve("armorstand")
                .resolve("templates.yml");
        this.templates = new TemplateStore(templateFile, ctx.logger());
        try {
            templates.load();
        } catch (java.io.IOException e) {
            ctx.logger().warn("Armor stand templates could not be read; starting with none", e);
        }
        this.templateListener = new TemplateListener(ctx, config,
                templates, new org.bukkit.NamespacedKey(ctx.plugin(), "astand_template"));
        ctx.listen(templateListener);
        this.access = new StandAccess(config, ctx.services().get(ProtectionService.class).orElse(null));
        this.editor = new StandEditor(ctx, config, access);
        ctx.listen(this);

        ctx.permission("atrium.armorstand", PermissionDefault.OP, "Open the armor stand editor");
        ctx.permission("atrium.armorstand.equipment", PermissionDefault.OP, "Swap armor stand equipment with the main hand");
        ctx.permission("atrium.armorstand.poses", PermissionDefault.OP, "Save, load and delete named armor stand poses");
        ctx.permission("atrium.armorstand.copy", PermissionDefault.OP, "Copy and paste armor stand poses and flags");
        ctx.permission("atrium.armorstand.templates", PermissionDefault.OP,
                "Save, give and delete armor stand placement templates");
        ctx.permission(TemplateListener.PERM_ARMS, PermissionDefault.TRUE,
                "Get an armor stand with arms when placing one with arms enabled");

        SuggestionProvider<CommandSourceStack> poseNames = (c, builder) -> {
            if (!(c.getSource().getSender() instanceof Player player)) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            return store.names(player.getUniqueId()).handle((names, error) -> {
                if (names != null) {
                    names.stream().filter(n -> n.startsWith(typed)).forEach(builder::suggest);
                }
                return builder.build();
            });
        };
        var name = Commands.argument("name", StringArgumentType.word()).suggests(poseNames);
        ctx.command(CommandSpec.of(Cmds.literal("astand", "atrium.armorstand")
                .executes(this::open)
                .then(Cmds.literal("copy", "atrium.armorstand.copy").executes(this::copy))
                .then(Cmds.literal("paste", "atrium.armorstand.copy").executes(this::paste))
                .then(Cmds.literal("pose", "atrium.armorstand.poses")
                        .executes(this::poseUsage)
                        .then(Cmds.literal("save").executes(this::poseUsage).then(Commands.argument("name", StringArgumentType.word())
                                .suggests(poseNames).executes(this::save)))
                        .then(Cmds.literal("load").executes(this::poseUsage).then(name.executes(this::load)))
                        .then(Cmds.literal("delete").executes(this::poseUsage).then(Commands.argument("name", StringArgumentType.word())
                                .suggests(poseNames).executes(this::delete)))
                        .then(Cmds.literal("list").executes(this::list)))
                .then(Cmds.literal("template", "atrium.armorstand.templates")
                        .executes(this::templateUsage)
                        .then(Cmds.literal("list").executes(this::templateList))
                        .then(Cmds.literal("save")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(Cmds.suggest(templates::names))
                                        .executes(this::templateSave)))
                        .then(Cmds.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(Cmds.suggest(templates::names))
                                        .executes(this::templateDelete)))
                        .then(Cmds.literal("give")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(Cmds.suggest(templates::names))
                                        .executes(this::templateGive))))
                .build(), "Open the editor for the armor stand you look at; copy, paste and save poses", "armorstand"));
    }

    @Override
    public void disable() {
        // Open editors are menus of this module: the menu service closes them on disable.
        clipboard.clear();
    }

    /** Looks up the targeted stand on the player's thread or fails with a message. */
    private ArmorStand target(CommandContext<CommandSourceStack> c, Player player) throws CommandSyntaxException {
        StandAccess.Target target = access.find(player);
        return switch (target.lookup()) {
            case FOUND -> target.stand();
            case ELSEWHERE -> throw cmds.fail(c.getSource(), "armorstand.stand-elsewhere");
            case NONE -> throw cmds.fail(c.getSource(), "armorstand.no-stand",
                    Msg.unparsed("reach", StandEditor.format(access.reach())));
        };
    }

    private int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ArmorStand stand = target(c, player);
        editor.open(player, stand);
        return Cmds.OK;
    }

    private int poseUsage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "armorstand.usage-pose");
    }

    private int copy(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ArmorStand stand = target(c, player);
        clipboard.put(player.getUniqueId(), new Clipboard(Pose.of(stand), Toggle.enabled(stand)));
        ctx.messages().send(player, "armorstand.copied");
        return Cmds.OK;
    }

    private int paste(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Clipboard copied = clipboard.get(player.getUniqueId());
        if (copied == null) {
            throw cmds.fail(c.getSource(), "armorstand.clipboard-empty");
        }
        ArmorStand stand = target(c, player);
        if (!access.permitted(player, stand, Action.APPLY_POSE, "clipboard")) {
            throw cmds.fail(c.getSource(), "armorstand.denied");
        }
        copied.pose().apply(stand);
        Toggle.applyAll(stand, copied.toggles());
        ctx.messages().send(player, "armorstand.pasted");
        return Cmds.OK;
    }

    private String poseName(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (!PoseStore.validName(name)) {
            throw cmds.fail(c.getSource(), "armorstand.pose-invalid-name");
        }
        return name;
    }

    private int save(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = poseName(c);
        Pose pose = Pose.of(target(c, player));
        int limit = Permissions.limit(player, "atrium.armorstand.poses", Math.max(0, config.get().maxSavedPoses));
        store.save(player.getUniqueId(), name, pose, limit).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot save armor stand pose '{}' of {}", name, player.getUniqueId(), error);
                reply(player, "armorstand.storage-error");
                return;
            }
            switch (result) {
                case LIMIT -> reply(player, "armorstand.pose-limit", Msg.number("limit", limit));
                case REPLACED -> reply(player, "armorstand.pose-replaced", Msg.unparsed("name", name));
                case CREATED -> reply(player, "armorstand.pose-saved", Msg.unparsed("name", name));
            }
        });
        return Cmds.OK;
    }

    private int load(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = poseName(c);
        store.load(player.getUniqueId(), name).whenComplete((pose, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot load armor stand pose '{}' of {}", name, player.getUniqueId(), error);
                reply(player, "armorstand.storage-error");
                return;
            }
            if (pose.isEmpty()) {
                reply(player, "armorstand.pose-not-found", Msg.unparsed("name", name));
                return;
            }
            // Back on the player's thread: the stand is looked up again now, not trusted from earlier.
            ctx.scheduler().entity(player, () -> {
                StandAccess.Target target = access.find(player);
                if (target.lookup() != StandAccess.Lookup.FOUND || target.stand() == null) {
                    ctx.messages().send(player, target.lookup() == StandAccess.Lookup.ELSEWHERE
                            ? "armorstand.stand-elsewhere" : "armorstand.no-stand",
                            Msg.unparsed("reach", StandEditor.format(access.reach())));
                    return;
                }
                if (!access.permitted(player, target.stand(), Action.APPLY_POSE, name)) {
                    ctx.messages().send(player, "armorstand.denied");
                    return;
                }
                pose.get().apply(target.stand());
                ctx.messages().send(player, "armorstand.pose-loaded", Msg.unparsed("name", name));
            }, null);
        });
        return Cmds.OK;
    }

    private int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = poseName(c);
        store.delete(player.getUniqueId(), name).whenComplete((deleted, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot delete armor stand pose '{}' of {}", name, player.getUniqueId(), error);
                reply(player, "armorstand.storage-error");
            } else {
                reply(player, deleted ? "armorstand.pose-deleted" : "armorstand.pose-not-found", Msg.unparsed("name", name));
            }
        });
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        store.names(player.getUniqueId()).whenComplete((names, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot list armor stand poses of {}", player.getUniqueId(), error);
                reply(player, "armorstand.storage-error");
            } else if (names.isEmpty()) {
                reply(player, "armorstand.pose-list-empty");
            } else {
                reply(player, "armorstand.pose-list", Msg.unparsed("poses", String.join(", ", names)),
                        Msg.number("count", names.size()));
            }
        });
        return Cmds.OK;
    }

    // ---- templates ----

    private int templateUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "armorstand.usage-template");
        return Cmds.OK;
    }

    private int templateList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        java.util.List<String> names = templates.names();
        if (names.isEmpty()) {
            ctx.messages().send(sender, "armorstand.template-list-empty");
        } else {
            ctx.messages().send(sender, "armorstand.template-list", Msg.number("count", names.size()),
                    Msg.unparsed("templates", String.join(", ", names)));
        }
        return Cmds.OK;
    }

    private int templateSave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (!TemplateStore.validName(name)) {
            throw cmds.fail(c.getSource(), "armorstand.template-invalid-name");
        }
        ArmorStand stand = target(c, player);
        if (!access.permitted(player, stand, Action.APPLY_POSE, name)) {
            throw cmds.fail(c.getSource(), "armorstand.denied");
        }
        StandTemplate template = StandTemplate.capture(name, stand);
        int limit = Permissions.limit(player, "atrium.armorstand.templates", Math.max(1, config.get().maxTemplates));
        if (templates.get(name).isEmpty() && templates.names().size() >= limit) {
            throw cmds.fail(c.getSource(), "armorstand.template-limit", Msg.number("limit", limit));
        }
        ctx.scheduler().supplyIo(() -> {
            templates.put(template);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot save armor stand template '{}'", name, error);
                reply(player, "armorstand.storage-error");
                return;
            }
            reply(player, "armorstand.template-saved", Msg.unparsed("name", name),
                    Msg.unparsed("templates-hint", "/astand template give " + name));
        });
        return Cmds.OK;
    }

    private int templateDelete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (!TemplateStore.validName(name)) {
            throw cmds.fail(c.getSource(), "armorstand.template-invalid-name");
        }
        ctx.scheduler().supplyIo(() -> templates.delete(name)).whenComplete((deleted, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot delete armor stand template '{}'", name, error);
                reply(player, "armorstand.storage-error");
                return;
            }
            reply(player, Boolean.TRUE.equals(deleted) ? "armorstand.template-deleted"
                    : "armorstand.template-not-found", Msg.unparsed("name", name));
        });
        return Cmds.OK;
    }

    private int templateGive(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (templates.get(name).isEmpty()) {
            throw cmds.fail(c.getSource(), "armorstand.template-not-found", Msg.unparsed("name", name));
        }
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARMOR_STAND);
        templateListener.tag(item, name);
        var leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            ctx.messages().send(player, "armorstand.template-inventory-full");
            return Cmds.OK;
        }
        ctx.messages().send(player, "armorstand.template-given", Msg.unparsed("name", name));
        return Cmds.OK;
    }

    private void reply(Player player, String key, TagResolver... tags) {
        ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clipboard.remove(event.getPlayer().getUniqueId());
    }
}
