package dev.yanianz.atrium.module.interactive;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;

/** Interactive commands: action lines run by clicking bound blocks, entities or tagged items. */
public final class InteractiveModule implements AtriumModule {
    static final String PERM_ADMIN = "atrium.interactive.admin";

    private ModuleContext ctx;
    private Cmds cmds;
    private Messages messages;
    private ConfigHandle<InteractiveConfig> config;
    private InteractiveStore store;
    private ActionService actions;
    private NamespacedKey itemKey;
    final InteractiveRegistry registry = new InteractiveRegistry();
    /** Pending "click the block/entity" selections by player. */
    final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    /** A pending bind ({@code name != null}) or unbind ({@code name == null}). */
    record Selection(InteractiveCommand.TargetKind kind, String name, long expiresAt) {}

    @Override
    public String id() {
        return "interactive";
    }

    @Override
    public String description() {
        return "Interactive blocks, entities and items (/ic)";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("actions");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.config = ctx.config(InteractiveConfig.class, InteractiveConfig::new);
        this.actions = ctx.services().require(ActionService.class);
        this.itemKey = new NamespacedKey(ctx.plugin(), "interactive");
        ctx.migrate(InteractiveStore.MIGRATIONS);
        this.store = new InteractiveStore(ctx.database());
        InteractiveStore.Snapshot snapshot = store.loadAll().get(30, TimeUnit.SECONDS);
        registry.clear();
        snapshot.commands().forEach(registry::put);
        for (InteractiveStore.Target target : snapshot.targets()) {
            try {
                registry.bind(target.kind(), target.target(), target.nameLower());
            } catch (IllegalArgumentException e) {
                ctx.logger().warn("Ignoring malformed interactive target {} {}: {}", target.kind(), target.target(), e.getMessage());
            }
        }
        for (InteractiveCommand command : registry.all()) {
            List<String> errors = actions.validate(command.actions());
            if (!errors.isEmpty()) {
                ctx.logger().warn("Interactive command '{}' has invalid actions (it will not run until fixed): {}",
                        command.name(), String.join("; ", errors));
            }
        }

        ctx.permission(PERM_ADMIN, PermissionDefault.OP, "Create and edit interactive commands (/ic)");
        ctx.listen(new InteractiveListener(this, ctx, config, actions));
        ctx.command(CommandSpec.of(Cmds.literal("ic", PERM_ADMIN)
                .executes(this::list)
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("info").then(nameArg().executes(this::info)))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(this::create)))
                .then(Commands.literal("delete").then(nameArg().executes(this::delete)))
                .then(Commands.literal("bind").then(nameArg()
                        .then(Commands.literal("block").executes(c -> select(c, InteractiveCommand.TargetKind.BLOCK, true)))
                        .then(Commands.literal("entity").executes(c -> select(c, InteractiveCommand.TargetKind.ENTITY, true)))
                        .then(Commands.literal("item").executes(this::bindItem))))
                .then(Commands.literal("unbind")
                        .then(Commands.literal("block").executes(c -> select(c, InteractiveCommand.TargetKind.BLOCK, false)))
                        .then(Commands.literal("entity").executes(c -> select(c, InteractiveCommand.TargetKind.ENTITY, false)))
                        .then(Commands.literal("item").executes(this::unbindItem)))
                .then(Commands.literal("actions").then(nameArg()
                        .executes(this::listActions)
                        .then(Commands.literal("list").executes(this::listActions))
                        .then(Commands.literal("add").then(Commands.argument("line", StringArgumentType.greedyString())
                                .executes(this::addAction)))
                        .then(Commands.literal("remove").then(Commands.argument("index", IntegerArgumentType.integer(1))
                                .suggests(indexes())
                                .executes(this::removeAction)))))
                .then(Commands.literal("set").then(nameArg()
                        .then(Commands.literal("permission").then(Commands.argument("node", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("none")))
                                .executes(this::setPermission)))
                        .then(Commands.literal("cooldown").then(Commands.argument("duration", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("0", "30s", "5m", "1h")))
                                .executes(this::setCooldown)))
                        .then(Commands.literal("cancel-event").then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(this::setCancel)))
                        .then(Commands.literal("trigger").then(Commands.argument("trigger", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("left", "right", "both", "physical")))
                                .executes(this::setTrigger)))))
                .build(), "Create and manage interactive commands bound to blocks, entities and items", "interactive"));
        ctx.logger().info("Loaded {} interactive commands", registry.all().size());
    }

    @Override
    public void disable() {
        selections.clear();
    }

    NamespacedKey itemKey() {
        return itemKey;
    }

    Optional<String> itemTag(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    // ---- shared helpers ----

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> nameArg() {
        return Commands.argument("name", StringArgumentType.word()).suggests(names());
    }

    private SuggestionProvider<CommandSourceStack> names() {
        return Cmds.suggest(registry::names);
    }

    /** Suggests the 1-based action numbers of the command named earlier in the same input. */
    private SuggestionProvider<CommandSourceStack> indexes() {
        return (c, builder) -> {
            registry.get(StringArgumentType.getString(c, "name")).ifPresent(command -> {
                for (int i = 1; i <= command.actions().size(); i++) {
                    builder.suggest(Integer.toString(i));
                }
            });
            return builder.buildFuture();
        };
    }

    private InteractiveCommand existing(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name");
        return registry.get(name).orElseThrow(() -> cmds.fail(c.getSource(), "interactive.not-found", Msg.unparsed("name", name)));
    }

    /** Storage writes run one after another, so two quick edits never overwrite each other. */
    private CompletableFuture<?> writes = CompletableFuture.completedFuture(null);

    private synchronized <T> CompletableFuture<T> serial(java.util.function.Supplier<CompletableFuture<T>> write) {
        CompletableFuture<T> next = writes.handle((ignored, error) -> null).thenCompose(ignored -> write.get());
        writes = next;
        return next;
    }

    /**
     * Applies {@code change} to the current version of the command, saves it, then updates memory and reports
     * {@code key}. A failed save changes nothing.
     */
    private void edit(CommandSender sender, String name, java.util.function.UnaryOperator<InteractiveCommand> change, String key,
            TagResolver... resolvers) {
        serial(() -> {
            Optional<InteractiveCommand> current = registry.get(name);
            if (current.isEmpty()) {
                messages.send(sender, "interactive.not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            InteractiveCommand updated;
            try {
                updated = change.apply(current.get());
            } catch (IndexOutOfBoundsException e) {
                messages.send(sender, "interactive.action-index", Msg.number("count", current.get().actions().size()));
                return CompletableFuture.completedFuture(null);
            }
            return store.upsert(updated).whenComplete((ignored, error) -> {
                if (error != null) {
                    ctx.logger().error("Saving interactive command {} failed", updated.name(), error);
                    messages.send(sender, "interactive.save-failed");
                    return;
                }
                registry.put(updated);
                send(sender, key, updated, resolvers);
            });
        });
    }

    private void send(CommandSender sender, String key, InteractiveCommand command, TagResolver... resolvers) {
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        System.arraycopy(resolvers, 0, all, 0, resolvers.length);
        all[resolvers.length] = Msg.unparsed("name", command.name());
        messages.send(sender, key, all);
    }

    private <T> void report(CommandSender sender, java.util.function.Supplier<CompletableFuture<T>> write, String what,
            Consumer<T> onSuccess) {
        serial(write).whenComplete((value, error) -> {
            if (error != null) {
                ctx.logger().error("Interactive command storage failed ({})", what, error);
                messages.send(sender, "interactive.save-failed");
                return;
            }
            onSuccess.accept(value);
        });
    }

    // ---- /ic list, info ----

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<InteractiveCommand> all = registry.all();
        messages.send(sender, "interactive.list-header", Msg.number("count", all.size()));
        if (all.isEmpty()) {
            messages.send(sender, "interactive.list-empty");
        }
        for (InteractiveCommand command : all) {
            send(sender, "interactive.list-entry", command, Msg.number("actions", command.actions().size()),
                    Msg.number("blocks", registry.blocksOf(command.name()).size()),
                    Msg.number("entities", registry.entitiesOf(command.name()).size()),
                    Msg.unparsed("trigger", command.trigger().name().toLowerCase(Locale.ROOT)));
        }
        return Cmds.OK;
    }

    private int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        InteractiveCommand command = existing(c);
        send(sender, "interactive.info", command,
                Msg.unparsed("permission", command.permission().isEmpty() ? "-" : command.permission()),
                Msg.unparsed("cooldown", command.cooldown().isZero() ? "-" : Durations.format(command.cooldown(), 3)),
                Msg.unparsed("cancel_event", Boolean.toString(command.cancelEvent())),
                Msg.unparsed("trigger", command.trigger().name().toLowerCase(Locale.ROOT)),
                Msg.number("actions", command.actions().size()));
        for (BlockKey block : registry.blocksOf(command.name())) {
            messages.send(sender, "interactive.info-block", Msg.unparsed("block", block.toString()));
        }
        for (UUID entity : registry.entitiesOf(command.name())) {
            messages.send(sender, "interactive.info-entity", Msg.unparsed("entity", entity.toString()));
        }
        return Cmds.OK;
    }

    // ---- create / delete ----

    private int create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        if (!InteractiveCommand.validName(name)) {
            throw cmds.fail(c.getSource(), "interactive.invalid-name");
        }
        if (registry.get(name).isPresent()) {
            throw cmds.fail(c.getSource(), "interactive.exists", Msg.unparsed("name", name));
        }
        InteractiveCommand command = InteractiveCommand.create(name, config.get().defaultCancelEvent);
        report(sender, () -> store.insert(command), "create " + name, inserted -> {
            if (!inserted) {
                messages.send(sender, "interactive.exists", Msg.unparsed("name", name));
                return;
            }
            registry.put(command);
            send(sender, "interactive.created", command);
        });
        return Cmds.OK;
    }

    private int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        InteractiveCommand command = existing(c);
        report(sender, () -> store.delete(command.name()), "delete " + command.name(), deleted -> {
            registry.remove(command.name());
            send(sender, "interactive.deleted", command);
        });
        return Cmds.OK;
    }

    // ---- binding ----

    private int select(CommandContext<CommandSourceStack> c, InteractiveCommand.TargetKind kind, boolean bind)
            throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = null;
        if (bind) {
            name = existing(c).name();
        }
        long expires = System.currentTimeMillis() + Math.max(5, config.get().selectionTimeoutSeconds) * 1000L;
        selections.put(player.getUniqueId(), new Selection(kind, name, expires));
        messages.send(player, "interactive.select-" + (bind ? "" : "unbind-") + kind.name().toLowerCase(Locale.ROOT),
                Msg.unparsed("name", name == null ? "" : name), Msg.number("seconds", Math.max(5, config.get().selectionTimeoutSeconds)));
        return Cmds.OK;
    }

    /** Called by the listener on the player's thread when a selection click happened. */
    void completeSelection(Player player, Selection selection, String target, String targetDescription) {
        TagResolver where = Msg.unparsed("target", targetDescription);
        if (selection.name() == null) {
            Optional<String> bound = registry.boundTo(selection.kind(), target);
            if (bound.isEmpty()) {
                messages.send(player, "interactive.not-bound", where);
                return;
            }
            report(player, () -> store.unbind(selection.kind(), target), "unbind " + target, removed -> {
                registry.unbind(selection.kind(), target);
                messages.send(player, "interactive.unbound", where, Msg.unparsed("name", bound.get()));
            });
            return;
        }
        Optional<InteractiveCommand> command = registry.get(selection.name());
        if (command.isEmpty()) {
            messages.send(player, "interactive.not-found", Msg.unparsed("name", selection.name()));
            return;
        }
        Optional<String> bound = registry.boundTo(selection.kind(), target);
        if (bound.isPresent()) {
            messages.send(player, "interactive.already-bound", where, Msg.unparsed("name", bound.get()));
            return;
        }
        report(player, () -> store.bind(selection.kind(), target, command.get().name()), "bind " + target, inserted -> {
            if (!inserted) {
                messages.send(player, "interactive.already-bound", where, Msg.unparsed("name", "?"));
                return;
            }
            registry.bind(selection.kind(), target, command.get().name());
            send(player, "interactive.bound", command.get(), where);
        });
    }

    private int bindItem(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        InteractiveCommand command = existing(c);
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            throw cmds.fail(c.getSource(), "interactive.no-item");
        }
        item.editPersistentDataContainer(pdc -> pdc.set(itemKey, PersistentDataType.STRING, command.key()));
        player.getInventory().setItemInMainHand(item);
        send(player, "interactive.bound-item", command);
        return Cmds.OK;
    }

    private int unbindItem(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ItemStack item = player.getInventory().getItemInMainHand();
        if (itemTag(item).isEmpty()) {
            throw cmds.fail(c.getSource(), "interactive.item-not-bound");
        }
        item.editPersistentDataContainer(pdc -> pdc.remove(itemKey));
        player.getInventory().setItemInMainHand(item);
        messages.send(player, "interactive.item-unbound");
        return Cmds.OK;
    }

    // ---- actions ----

    private int listActions(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        InteractiveCommand command = existing(c);
        send(sender, "interactive.actions-header", command, Msg.number("count", command.actions().size()));
        if (command.actions().isEmpty()) {
            messages.send(sender, "interactive.actions-empty");
        }
        for (int i = 0; i < command.actions().size(); i++) {
            messages.send(sender, "interactive.action-entry", Msg.number("index", i + 1), Msg.unparsed("line", command.actions().get(i)));
        }
        return Cmds.OK;
    }

    private int addAction(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        InteractiveCommand command = existing(c);
        String line = StringArgumentType.getString(c, "line").strip();
        List<String> errors = actions.validate(List.of(line));
        if (!errors.isEmpty()) {
            throw cmds.fail(c.getSource(), "interactive.action-invalid", Msg.unparsed("error", errors.getFirst()));
        }
        if (command.actions().size() >= Math.max(1, config.get().maxActions)) {
            throw cmds.fail(c.getSource(), "interactive.too-many-actions", Msg.number("max", config.get().maxActions));
        }
        edit(sender, command.name(), current -> current.withAction(line), "interactive.action-added", Msg.unparsed("line", line));
        return Cmds.OK;
    }

    private int removeAction(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        InteractiveCommand command = existing(c);
        int index = IntegerArgumentType.getInteger(c, "index");
        if (index > command.actions().size()) {
            throw cmds.fail(c.getSource(), "interactive.action-index", Msg.number("count", command.actions().size()));
        }
        String removed = command.actions().get(index - 1);
        edit(sender, command.name(), current -> current.withoutAction(index), "interactive.action-removed", Msg.number("index", index), Msg.unparsed("line", removed));
        return Cmds.OK;
    }

    // ---- settings ----

    private int setPermission(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        InteractiveCommand command = existing(c);
        String node = StringArgumentType.getString(c, "node");
        String value = node.equalsIgnoreCase("none") ? "" : node;
        if (!value.isEmpty() && !InteractiveCommand.PERMISSION.matcher(value).matches()) {
            throw cmds.fail(c.getSource(), "interactive.invalid-permission");
        }
        edit(c.getSource().getSender(), command.name(), current -> current.withPermission(value), "interactive.set-permission",
                Msg.unparsed("permission", value.isEmpty() ? "-" : value));
        return Cmds.OK;
    }

    private int setCooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        InteractiveCommand command = existing(c);
        String text = StringArgumentType.getString(c, "duration");
        Duration duration = Durations.parse(text)
                .orElseThrow(() -> cmds.fail(c.getSource(), "interactive.invalid-duration", Msg.unparsed("value", text)));
        edit(c.getSource().getSender(), command.name(), current -> current.withCooldown(duration), "interactive.set-cooldown",
                Msg.unparsed("cooldown", duration.isZero() ? "-" : Durations.format(duration, 3)));
        return Cmds.OK;
    }

    private int setCancel(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        InteractiveCommand command = existing(c);
        boolean value = BoolArgumentType.getBool(c, "value");
        edit(c.getSource().getSender(), command.name(), current -> current.withCancelEvent(value), "interactive.set-cancel-event",
                Msg.unparsed("value", Boolean.toString(value)));
        return Cmds.OK;
    }

    private int setTrigger(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        InteractiveCommand command = existing(c);
        String text = StringArgumentType.getString(c, "trigger");
        InteractiveCommand.Trigger trigger;
        try {
            trigger = InteractiveCommand.Trigger.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "interactive.invalid-trigger", Msg.unparsed("value", text));
        }
        edit(c.getSource().getSender(), command.name(), current -> current.withTrigger(trigger), "interactive.set-trigger",
                Msg.unparsed("trigger", trigger.name().toLowerCase(Locale.ROOT)));
        return Cmds.OK;
    }
}
