package dev.yanianz.atrium.module.inventories;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * /invsave, /invlist, /invcheck, /invload, /invremove, /invremoveall and /clearender.
 *
 * <p>Commands run on the sender's thread. Every world change is scheduled on the target's own thread; storage
 * and item decoding stay on the I/O thread. Destructive actions ask for the same command twice, and a load
 * always writes a backup before it touches the inventory.
 */
final class InventoryCommands implements Listener, TaskHandle {
    private static final String PERM_SAVE = "atrium.invsave";
    private static final String PERM_LIST = "atrium.invlist";
    private static final String PERM_CHECK = "atrium.invcheck";
    private static final String PERM_LOAD = "atrium.invload";
    private static final String PERM_REMOVE = "atrium.invremove";
    private static final String PERM_REMOVE_ALL = "atrium.invremoveall";
    private static final String PERM_CLEAR_ENDER = "atrium.clearender";
    private static final String PERM_CLEAR_ENDER_OTHERS = "atrium.clearender.others";
    private static final String PERM_SILENT = "atrium.inventories.silent";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<InventoriesConfig> config;
    private final SnapshotStore store;
    private final SnapshotPreview preview;
    private final Confirmations confirmations = new Confirmations();
    private volatile boolean cancelled;

    private record Saved(long id, String playerName, int stacks) {}

    InventoryCommands(ModuleContext ctx, ConfigHandle<InventoriesConfig> config, SnapshotStore store,
            SnapshotPreview preview) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.store = store;
        this.preview = preview;
    }

    void register() {
        ctx.track(this);
        ctx.listen(this);
        registerPermissions();

        LiteralArgumentBuilder<CommandSourceStack> save = Cmds.literal("invsave", PERM_SAVE);
        save.executes(usage("invsave"));
        save.then(saveChain(false));
        save.then(Cmds.literal("-s", PERM_SILENT).executes(usage("invsave")).then(saveChain(true)));
        ctx.command(CommandSpec.of(save.build(), "Save a snapshot of a player's inventory and ender chest"));

        LiteralArgumentBuilder<CommandSourceStack> list = Cmds.literal("invlist", PERM_LIST);
        list.executes(usage("invlist"));
        list.then(listChain());
        ctx.command(CommandSpec.of(list.build(), "List the saved inventory snapshots of a player"));

        LiteralArgumentBuilder<CommandSourceStack> check = Cmds.literal("invcheck", PERM_CHECK);
        check.executes(usage("invcheck"));
        check.then(checkChain());
        ctx.command(CommandSpec.of(check.build(), "Open a read-only preview of a saved inventory snapshot"));

        LiteralArgumentBuilder<CommandSourceStack> load = Cmds.literal("invload", PERM_LOAD);
        load.executes(usage("invload"));
        load.then(loadChain(false));
        load.then(Cmds.literal("-s", PERM_SILENT).executes(usage("invload")).then(loadChain(true)));
        ctx.command(CommandSpec.of(load.build(), "Restore a saved snapshot to a player (replace, or merge with -m)",
                "invrestore"));

        LiteralArgumentBuilder<CommandSourceStack> remove = Cmds.literal("invremove", PERM_REMOVE);
        remove.executes(usage("invremove"));
        remove.then(removeChain(false));
        remove.then(Cmds.literal("-s", PERM_SILENT).executes(usage("invremove")).then(removeChain(true)));
        ctx.command(CommandSpec.of(remove.build(), "Delete one saved inventory snapshot"));

        LiteralArgumentBuilder<CommandSourceStack> removeAll = Cmds.literal("invremoveall", PERM_REMOVE_ALL);
        removeAll.executes(usage("invremoveall"));
        removeAll.then(removeAllChain(false));
        removeAll.then(Cmds.literal("-s", PERM_SILENT).executes(usage("invremoveall")).then(removeAllChain(true)));
        ctx.command(CommandSpec.of(removeAll.build(), "Delete every saved inventory snapshot of a player"));

        LiteralArgumentBuilder<CommandSourceStack> clearEnder = Cmds.literal("clearender", PERM_CLEAR_ENDER);
        clearEnder.executes(usage("clearender"));
        clearEnder.then(clearEnderChain(false));
        clearEnder.then(Cmds.literal("-s", PERM_SILENT).executes(usage("clearender")).then(clearEnderChain(true)));
        ctx.command(CommandSpec.of(clearEnder.build(), "Back up and clear the ender chest of a player"));
    }

    private void registerPermissions() {
        ctx.permission(PERM_SAVE, org.bukkit.permissions.PermissionDefault.OP,
                "Save an inventory snapshot of a player");
        ctx.permission(PERM_LIST, org.bukkit.permissions.PermissionDefault.OP,
                "List the saved inventory snapshots of a player");
        ctx.permission(PERM_CHECK, org.bukkit.permissions.PermissionDefault.OP,
                "Preview a saved inventory snapshot");
        ctx.permission(PERM_LOAD, org.bukkit.permissions.PermissionDefault.OP,
                "Restore a saved inventory snapshot");
        ctx.permission(PERM_REMOVE, org.bukkit.permissions.PermissionDefault.OP,
                "Delete one saved inventory snapshot");
        ctx.permission(PERM_REMOVE_ALL, org.bukkit.permissions.PermissionDefault.OP,
                "Delete every saved inventory snapshot of a player");
        ctx.permission(PERM_CLEAR_ENDER, org.bukkit.permissions.PermissionDefault.OP,
                "Clear your own ender chest after taking a backup");
        ctx.permission(PERM_CLEAR_ENDER_OTHERS, org.bukkit.permissions.PermissionDefault.OP,
                "Clear the ender chest of another player after taking a backup");
        ctx.permission(PERM_SILENT, org.bukkit.permissions.PermissionDefault.OP,
                "Use the -s silent flag on inventory snapshot commands");
    }

    // ---- command trees ----

    private ArgumentBuilder<CommandSourceStack, ?> saveChain(boolean silent) {
        var player = Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(c -> save(c, silent));
        player.then(Commands.argument("label", StringArgumentType.greedyString())
                .executes(c -> save(c, silent)));
        return player;
    }

    private ArgumentBuilder<CommandSourceStack, ?> listChain() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(c -> list(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(c -> list(c, IntegerArgumentType.getInteger(c, "page"))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> checkChain() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(usage("invcheck"))
                .then(Commands.argument("id", LongArgumentType.longArg(1)).suggests(snapshotIds())
                        .executes(this::check));
    }

    private ArgumentBuilder<CommandSourceStack, ?> loadChain(boolean silent) {
        var id = Commands.argument("id", LongArgumentType.longArg(1)).suggests(snapshotIds())
                .executes(c -> load(c, false, silent));
        id.then(Commands.literal("--merge").executes(c -> load(c, true, silent)));
        id.then(Commands.literal("-m").executes(c -> load(c, true, silent)));
        return Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(usage("invload"))
                .then(id);
    }

    private ArgumentBuilder<CommandSourceStack, ?> removeChain(boolean silent) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(usage("invremove"))
                .then(Commands.argument("id", LongArgumentType.longArg(1)).suggests(snapshotIds())
                        .executes(c -> remove(c, silent)));
    }

    private ArgumentBuilder<CommandSourceStack, ?> removeAllChain(boolean silent) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(c -> removeAll(c, silent));
    }

    private ArgumentBuilder<CommandSourceStack, ?> clearEnderChain(boolean silent) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()))
                .executes(c -> clearEnder(c, silent));
    }

    /** Shows the usage line of a command (bare root or a missing argument). */
    private com.mojang.brigadier.Command<CommandSourceStack> usage(String command) {
        return c -> {
            ctx.messages().send(c.getSource().getSender(), "inventories.usage-" + command);
            return Cmds.OK;
        };
    }

    /** Suggests the newest snapshot ids of the player typed before (asynchronously, from the database). */
    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> snapshotIds() {
        return (c, builder) -> {
            String name;
            try {
                name = StringArgumentType.getString(c, "player");
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            return ctx.users().resolveAccount(name, false)
                    .thenCompose(ref -> ref.isEmpty()
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : store.listPage(ref.get().uuid(), 1, 20))
                    .thenApply(page -> {
                        if (page != null) {
                            String typed = builder.getRemaining();
                            for (SnapshotStore.Meta meta : page.entries()) {
                                String id = Long.toString(meta.id());
                                if (id.startsWith(typed)) {
                                    builder.suggest(id);
                                }
                            }
                        }
                        return builder.build();
                    })
                    .exceptionally(error -> {
                        ctx.logger().debug("Snapshot id suggestions for {} failed", name, error);
                        return builder.build();
                    });
        };
    }

    // ---- /invsave ----

    private int save(CommandContext<CommandSourceStack> c, boolean silent) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        String label = null;
        if (Cmds.hasArgument(c, "label")) {
            String raw = StringArgumentType.getString(c, "label");
            label = Labels.clean(raw, InventoriesConfig.labelLimit(config.get()));
            if (label == null || raw.strip().length() > InventoriesConfig.labelLimit(config.get())) {
                throw cmds.fail(c.getSource(), "inventories.invalid-label",
                        Msg.number("max", InventoriesConfig.labelLimit(config.get())));
            }
        }
        String finalLabel = label;
        resolve(sender, rawName, ref -> {
            Player online = Bukkit.getPlayer(ref.uuid());
            if (online == null) {
                copyLatest(sender, ref, finalLabel, silent);
                return;
            }
            ctx.scheduler().supplyOnEntity(online, () -> ItemStackSnapshots.capture(online))
                    .thenCompose(captured -> {
                        SnapshotStore.Draft draft = draft(ref, sender, finalLabel, SnapshotReason.MANUAL, captured);
                        return store.insert(draft).thenApply(id -> new Saved(id, ref.name(), draft.stacks()));
                    })
                    .whenComplete((saved, error) -> {
                        if (error != null) {
                            if (retired(error)) {
                                reply(sender, "target-left");
                                return;
                            }
                            failed(sender, "Saving a snapshot of " + ref.name(), error);
                            return;
                        }
                        prune(ref.uuid());
                        if (!silent) {
                            reply(sender, "saved", Msg.number("id", saved.id()),
                                    Msg.unparsed("player", saved.playerName()), Msg.number("stacks", saved.stacks()));
                        }
                    });
        });
        return Cmds.OK;
    }

    /** Offline save: copy the newest stored state under the new label (Paper cannot read offline inventories). */
    private void copyLatest(CommandSender sender, PlayerRef ref, String label, boolean silent) {
        store.latest(ref.uuid()).thenCompose(latest -> {
            if (latest.isEmpty()) {
                reply(sender, "offline-no-data", Msg.unparsed("player", ref.name()));
                return CompletableFuture.completedFuture(null);
            }
            SnapshotStore.Stored stored = latest.get();
            if (stored.meta().format() > SnapshotCodec.FORMAT) {
                reply(sender, "newer-format", Msg.number("id", stored.meta().id()),
                        Msg.unparsed("player", ref.name()), Msg.number("format", stored.meta().format()),
                        Msg.number("supported", SnapshotCodec.FORMAT));
                return CompletableFuture.completedFuture(null);
            }
            SnapshotStore.Draft draft = new SnapshotStore.Draft(ref.uuid(), ref.name(), stored.meta().format(),
                    System.currentTimeMillis(), creatorId(sender), creatorName(sender), label,
                    SnapshotReason.OFFLINE_COPY, stored.meta().stacks(), stored.items());
            return store.insert(draft);
        }).whenComplete((id, error) -> {
            if (error != null) {
                failed(sender, "Copying the last snapshot of " + ref.name(), error);
                return;
            }
            if (id == null) {
                return;
            }
            prune(ref.uuid());
            if (!silent) {
                reply(sender, "saved-copy", Msg.number("id", id), Msg.unparsed("player", ref.name()));
            }
        });
    }

    // ---- /invlist ----

    private int list(CommandContext<CommandSourceStack> c, int requestedPage) {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        resolve(sender, rawName, ref -> {
            int pageSize = InventoriesConfig.pageSize(config.get());
            store.listPage(ref.uuid(), requestedPage, pageSize).whenComplete((page, error) -> {
                if (error != null) {
                    failed(sender, "Listing snapshots of " + ref.name(), error);
                    return;
                }
                onSender(sender, () -> renderList(sender, ref.name(), page));
            });
        });
        return Cmds.OK;
    }

    private void renderList(CommandSender sender, String playerName, SnapshotStore.Page page) {
        if (page.total() == 0) {
            ctx.messages().send(sender, "inventories.list-empty", Msg.unparsed("player", playerName));
            return;
        }
        ctx.messages().send(sender, "inventories.list-header", Msg.unparsed("player", playerName),
                Msg.number("total", page.total()), Msg.number("page", page.page()),
                Msg.number("pages", page.pages()));
        boolean clickable = Labels.validName(playerName);
        for (SnapshotStore.Meta meta : page.entries()) {
            Component reason = ctx.messages().render(sender, "inventories.reason." + meta.reason().key());
            Component label = meta.label() == null
                    ? ctx.messages().render(sender, "inventories.list-no-label")
                    : Component.text(meta.label());
            Component line = ctx.messages().render(sender, "inventories.list-entry",
                    Msg.number("id", meta.id()), Msg.component("label", label), Msg.component("reason", reason),
                    Msg.unparsed("date", SnapshotText.time(meta.created())),
                    Msg.number("stacks", meta.stacks()));
            if (clickable) {
                line = line.clickEvent(ClickEvent.runCommand("/invcheck " + playerName + " " + meta.id()))
                        .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "inventories.list-hover",
                                Msg.unparsed("player", playerName), Msg.number("id", meta.id()),
                                Msg.component("label", label), Msg.component("reason", reason),
                                Msg.unparsed("date", SnapshotText.time(meta.created())),
                                Msg.number("stacks", meta.stacks()))));
            }
            Component actions = Component.empty();
            if (clickable && sender.hasPermission(PERM_LOAD)) {
                actions = actions.append(button(sender, "list-load", "/invload " + playerName + " " + meta.id()));
            }
            if (clickable && sender.hasPermission(PERM_REMOVE)) {
                actions = actions.append(button(sender, "list-remove", "/invremove " + playerName + " " + meta.id()));
            }
            sender.sendMessage(line.append(actions));
        }
        Component previous = page.page() > 1 && clickable
                ? button(sender, "list-prev", "/invlist " + playerName + " " + (page.page() - 1))
                : ctx.messages().render(sender, "inventories.list-prev-disabled");
        Component next = page.page() < page.pages() && clickable
                ? button(sender, "list-next", "/invlist " + playerName + " " + (page.page() + 1))
                : ctx.messages().render(sender, "inventories.list-next-disabled");
        Component footer = ctx.messages().render(sender, "inventories.list-footer",
                Msg.number("page", page.page()), Msg.number("pages", page.pages()),
                Msg.component("prev", previous), Msg.component("next", next));
        sender.sendMessage(footer);
    }

    private Component button(CommandSender sender, String key, String command) {
        return ctx.messages().render(sender, "inventories." + key).clickEvent(ClickEvent.runCommand(command));
    }

    // ---- /invcheck ----

    private int check(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player viewer = cmds.player(c.getSource());
        String rawName = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        resolve(viewer, rawName, ref -> store.get(ref.uuid(), id).whenComplete((found, error) -> {
            if (error != null) {
                failed(viewer, "Reading snapshot #" + id + " of " + ref.name(), error);
                return;
            }
            if (found.isEmpty()) {
                reply(viewer, "snapshot-missing", Msg.number("id", id), Msg.unparsed("player", ref.name()));
                return;
            }
            SnapshotStore.Stored stored = found.get();
            if (stored.meta().format() > SnapshotCodec.FORMAT) {
                reply(viewer, "newer-format", Msg.number("id", id), Msg.unparsed("player", ref.name()),
                        Msg.number("format", stored.meta().format()), Msg.number("supported", SnapshotCodec.FORMAT));
                return;
            }
            onSender(viewer, () -> {
                try {
                    SnapshotCodec.Contents<ItemStack> contents = ItemStackSnapshots.decode(stored.items());
                    preview.open(viewer, stored.meta(), contents);
                } catch (SnapshotCodec.SnapshotFormatException e) {
                    ctx.logger().warn("Snapshot #{} of {} ({}) cannot be read: {}", id, ref.name(), ref.uuid(),
                            e.getMessage());
                    reply(viewer, "corrupt", Msg.number("id", id), Msg.unparsed("player", ref.name()));
                } catch (RuntimeException e) {
                    ctx.logger().error("Opening the preview of snapshot #{} of {} failed", id, ref.uuid(), e);
                    reply(viewer, "failed");
                }
            });
        }));
        return Cmds.OK;
    }

    // ---- /invload ----

    private int load(CommandContext<CommandSourceStack> c, boolean merge, boolean silent) {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        resolve(sender, rawName, ref -> {
            Player target = Bukkit.getPlayer(ref.uuid());
            if (target == null) {
                reply(sender, "must-be-online", Msg.unparsed("player", ref.name()));
                return;
            }
            store.get(ref.uuid(), id).whenComplete((found, error) -> {
                if (error != null) {
                    failed(sender, "Reading snapshot #" + id + " of " + ref.name(), error);
                    return;
                }
                if (found.isEmpty()) {
                    reply(sender, "snapshot-missing", Msg.number("id", id), Msg.unparsed("player", ref.name()));
                    return;
                }
                SnapshotStore.Stored stored = found.get();
                if (stored.meta().format() > SnapshotCodec.FORMAT) {
                    reply(sender, "newer-format", Msg.number("id", id), Msg.unparsed("player", ref.name()),
                            Msg.number("format", stored.meta().format()), Msg.number("supported", SnapshotCodec.FORMAT));
                    return;
                }
                int seconds = InventoriesConfig.confirmationSeconds(config.get());
                String key = "load|" + ref.uuid() + "|" + id + "|" + merge;
                if (!confirmations.confirm(actor(sender), key, System.currentTimeMillis(), seconds * 1000L)) {
                    reply(sender, "confirm-load", Msg.unparsed("player", ref.name()), Msg.number("id", id),
                            Msg.number("seconds", seconds),
                            Msg.unparsed("mode", merge ? "merge" : "replace"));
                    return;
                }
                backupThenApply(sender, target, ref, stored, merge, silent);
            });
        });
        return Cmds.OK;
    }

    private record Prepared(SnapshotCodec.Contents<ItemStack> contents, ItemStackSnapshots.Captured captured) {}

    private record Backup(long id, Prepared prepared) {}

    private enum ApplyOutcome { APPLIED, OVERFLOW, CHANGED }

    /** What happened on the target's thread; {@code applied} carries the overflow counts of a load. */
    private record LoadResult(ApplyOutcome outcome, ItemStackSnapshots.Applied applied) {}

    private void backupThenApply(CommandSender sender, Player target, PlayerRef ref, SnapshotStore.Stored snapshot,
            boolean merge, boolean silent) {
        backupThenApply(sender, target, ref, snapshot, merge, silent, 1);
    }

    /**
     * Everything that touches the player (decode, capacity check, capture, apply) runs on the target's own thread.
     * The load only runs after the backup insert completed, and only while the live contents still equal that
     * backup: items picked up while the backup was being written trigger a fresh backup (bounded by
     * {@link BackupGuard#MAX_ATTEMPTS}), so an inventory is never replaced without an undo snapshot of exactly it.
     */
    private void backupThenApply(CommandSender sender, Player target, PlayerRef ref, SnapshotStore.Stored snapshot,
            boolean merge, boolean silent, int attempt) {
        ctx.scheduler().supplyOnEntity(target, () -> {
            SnapshotCodec.Contents<ItemStack> contents;
            try {
                contents = ItemStackSnapshots.decode(snapshot.items());
            } catch (SnapshotCodec.SnapshotFormatException e) {
                throw new ItemStackSnapshots.SnapshotFailure(e);
            }
            if (merge && !ItemStackSnapshots.canMerge(target, contents)) {
                return null;
            }
            return new Prepared(contents, ItemStackSnapshots.capture(target));
        }).thenCompose(prepared -> {
            if (prepared == null) {
                return CompletableFuture.completedFuture(null);
            }
            SnapshotStore.Draft draft = draft(ref, sender, null, SnapshotReason.PRE_LOAD, prepared.captured());
            return store.insert(draft).thenApply(id -> new Backup(id, prepared));
        }).whenComplete((backup, error) -> {
            if (error != null) {
                if (formatFailure(error)) {
                    ctx.logger().warn("Snapshot #{} of {} ({}) cannot be read: {}", snapshot.meta().id(),
                            ref.name(), ref.uuid(), error.getMessage());
                    reply(sender, "corrupt", Msg.number("id", snapshot.meta().id()),
                            Msg.unparsed("player", ref.name()));
                } else if (retired(error)) {
                    reply(sender, "target-left");
                } else {
                    failed(sender, "Backing up the inventory of " + ref.name() + " before a load", error);
                    reply(sender, "backup-failed");
                }
                return;
            }
            if (backup == null) {
                reply(sender, "merge-overflow", Msg.unparsed("player", ref.name()));
                return;
            }
            ctx.scheduler().supplyOnEntity(target, () -> {
                // Re-check on the target's thread: the backup must describe exactly what is about to be replaced.
                if (!ItemStackSnapshots.unchanged(target, backup.prepared().captured(), false)) {
                    return new LoadResult(ApplyOutcome.CHANGED, ItemStackSnapshots.Applied.REFUSED);
                }
                ItemStackSnapshots.Applied applied = ItemStackSnapshots.apply(target, backup.prepared().contents(),
                        merge);
                return new LoadResult(applied.applied() ? ApplyOutcome.APPLIED : ApplyOutcome.OVERFLOW, applied);
            }).whenComplete((result, applyError) -> {
                if (applyError != null) {
                    if (retired(applyError)) {
                        reply(sender, "target-left");
                    } else {
                        failed(sender, "Loading snapshot #" + snapshot.meta().id() + " into " + ref.name(),
                                applyError);
                    }
                    return;
                }
                ApplyOutcome outcome = result.outcome();
                if (outcome == ApplyOutcome.CHANGED) {
                    discardStaleBackup(ref, backup.id());
                    switch (BackupGuard.decide(false, attempt)) {
                        case REBACKUP -> {
                            ctx.logger().info("Inventory of {} changed while backup #{} was written; backing up again",
                                    ref.uuid(), backup.id());
                            backupThenApply(sender, target, ref, snapshot, merge, silent, attempt + 1);
                        }
                        default -> reply(sender, "changed-retry", Msg.unparsed("player", ref.name()));
                    }
                    return;
                }
                if (outcome != ApplyOutcome.APPLIED) {
                    reply(sender, "merge-overflow", Msg.unparsed("player", ref.name()));
                    return;
                }
                prune(ref.uuid());
                ItemStackSnapshots.Applied applied = result.applied();
                ctx.logger().info("Loaded snapshot #{} of {} ({}) with backup #{}", snapshot.meta().id(),
                        ref.uuid(), merge ? "merge" : "replace", backup.id());
                if (applied.overflow() > 0) {
                    ctx.logger().info("Snapshot #{} of {} had {} stack(s) beyond the live slot counts: moved into "
                            + "free inventory space, {} dropped at the player's feet", snapshot.meta().id(),
                            ref.uuid(), applied.overflow(), applied.dropped());
                }
                if (applied.dropped() > 0) {
                    notifyTarget(target, "overflow-dropped-target", Msg.number("dropped", applied.dropped()));
                }
                if (silent) {
                    return;
                }
                reply(sender, "loaded", Msg.number("id", snapshot.meta().id()),
                        Msg.unparsed("player", ref.name()), Msg.number("backup", backup.id()),
                        Msg.unparsed("mode", merge ? "merge" : "replace"));
                if (applied.overflow() > 0) {
                    reply(sender, "load-overflow", Msg.unparsed("player", ref.name()),
                            Msg.number("stacks", applied.overflow()),
                            Msg.number("moved", applied.overflow() - applied.dropped()),
                            Msg.number("dropped", applied.dropped()));
                }
                notifyTarget(target, "loaded-target", Msg.number("id", snapshot.meta().id()));
            });
        });
    }

    /**
     * Removes a backup that no longer matches the live contents (nothing was applied over it). Failure only
     * leaves an extra, still truthful, snapshot behind.
     */
    private void discardStaleBackup(PlayerRef ref, long id) {
        store.delete(ref.uuid(), id).whenComplete((deleted, error) -> {
            if (error != null) {
                ctx.logger().warn("Could not remove the outdated backup #{} of {}", id, ref.uuid(), error);
            }
        });
    }

    // ---- /invremove and /invremoveall ----

    private int remove(CommandContext<CommandSourceStack> c, boolean silent) {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        resolve(sender, rawName, ref -> store.delete(ref.uuid(), id).whenComplete((deleted, error) -> {
            if (error != null) {
                failed(sender, "Deleting snapshot #" + id + " of " + ref.name(), error);
                return;
            }
            if (!Boolean.TRUE.equals(deleted)) {
                reply(sender, "snapshot-missing", Msg.number("id", id), Msg.unparsed("player", ref.name()));
                return;
            }
            if (!silent) {
                reply(sender, "removed", Msg.number("id", id), Msg.unparsed("player", ref.name()));
            }
        }));
        return Cmds.OK;
    }

    private int removeAll(CommandContext<CommandSourceStack> c, boolean silent) {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        resolve(sender, rawName, ref -> {
            int seconds = InventoriesConfig.confirmationSeconds(config.get());
            String key = "removeall|" + ref.uuid();
            if (!confirmations.confirm(actor(sender), key, System.currentTimeMillis(), seconds * 1000L)) {
                reply(sender, "confirm-remove-all", Msg.unparsed("player", ref.name()), Msg.number("seconds", seconds));
                return;
            }
            store.deleteAll(ref.uuid()).whenComplete((count, error) -> {
                if (error != null) {
                    failed(sender, "Deleting every snapshot of " + ref.name(), error);
                    return;
                }
                if (!silent) {
                    reply(sender, "removed-all", Msg.unparsed("player", ref.name()), Msg.number("count", count));
                }
            });
        });
        return Cmds.OK;
    }

    // ---- /clearender ----

    private int clearEnder(CommandContext<CommandSourceStack> c, boolean silent) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        boolean ownName = sender instanceof Player self && rawName.equalsIgnoreCase(self.getName());
        if (!ownName && !sender.hasPermission(PERM_CLEAR_ENDER_OTHERS)) {
            throw cmds.fail(c.getSource(), "inventories.no-permission-others",
                    Msg.unparsed("permission", PERM_CLEAR_ENDER_OTHERS));
        }
        resolve(sender, rawName, ref -> {
            Player target = Bukkit.getPlayer(ref.uuid());
            if (target == null) {
                reply(sender, "must-be-online", Msg.unparsed("player", ref.name()));
                return;
            }
            boolean self = sender instanceof Player player && player.getUniqueId().equals(ref.uuid());
            int seconds = InventoriesConfig.confirmationSeconds(config.get());
            String key = "clearender|" + ref.uuid();
            ctx.scheduler().supplyOnEntity(target, () -> {
                if (target.getEnderChest().isEmpty()) {
                    return null;
                }
                return ItemStackSnapshots.capture(target);
            }).thenCompose(captured -> {
                if (captured == null) {
                    reply(sender, "ender-empty", Msg.unparsed("player", ref.name()));
                    return CompletableFuture.completedFuture(null);
                }
                if (!self && !confirmations.confirm(actor(sender), key, System.currentTimeMillis(),
                        seconds * 1000L)) {
                    reply(sender, "confirm-clear-ender", Msg.unparsed("player", ref.name()),
                            Msg.number("seconds", seconds));
                    return CompletableFuture.completedFuture(null);
                }
                backupThenClearEnder(sender, target, ref, captured, silent, 1);
                return CompletableFuture.completedFuture(null);
            }).whenComplete((unused, error) -> {
                if (error != null) {
                    if (retired(error)) {
                        reply(sender, "target-left");
                    } else {
                        failed(sender, "Reading the ender chest of " + ref.name(), error);
                    }
                }
            });
        });
        return Cmds.OK;
    }

    /**
     * Stores the ender chest backup, then clears on the target's thread only while the ender chest still equals
     * that backup; otherwise takes a fresh backup (bounded) or asks the admin to retry.
     */
    private void backupThenClearEnder(CommandSender sender, Player target, PlayerRef ref,
            ItemStackSnapshots.Captured captured, boolean silent, int attempt) {
        SnapshotStore.Draft draft = draft(ref, sender, null, SnapshotReason.CLEAR_ENDER, captured);
        store.insert(draft).whenComplete((backupId, error) -> {
            if (error != null) {
                failed(sender, "Backing up the ender chest of " + ref.name(), error);
                reply(sender, "backup-failed");
                return;
            }
            ctx.scheduler().entity(target, () -> {
                try {
                    if (!ItemStackSnapshots.unchanged(target, captured, true)) {
                        discardStaleBackup(ref, backupId);
                        if (BackupGuard.decide(false, attempt) == BackupGuard.Decision.REBACKUP) {
                            ctx.logger().info("Ender chest of {} changed while backup #{} was written; backing up again",
                                    ref.uuid(), backupId);
                            backupThenClearEnder(sender, target, ref, ItemStackSnapshots.capture(target), silent,
                                    attempt + 1);
                        } else {
                            reply(sender, "changed-retry", Msg.unparsed("player", ref.name()));
                        }
                        return;
                    }
                    target.getEnderChest().clear();
                    prune(ref.uuid());
                    ctx.logger().info("Cleared the ender chest of {} after backup #{}", ref.uuid(), backupId);
                    if (!silent) {
                        reply(sender, "cleared", Msg.unparsed("player", ref.name()),
                                Msg.number("backup", backupId));
                        notifyTarget(target, "cleared-target");
                    }
                } catch (RuntimeException e) {
                    failed(sender, "Clearing the ender chest of " + ref.name(), e);
                }
            }, () -> reply(sender, "target-left"));
        });
    }

    // ---- helpers ----

    private void resolve(CommandSender sender, String rawName, Consumer<PlayerRef> action) {
        ctx.users().resolveAccount(rawName, false).whenComplete((found, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                ctx.logger().warn("Resolving player {} for inventory snapshots failed", rawName, error);
                reply(sender, "failed");
                return;
            }
            if (found == null || found.isEmpty()) {
                onSender(sender, () -> ctx.messages().send(sender, "core.user-not-found", Msg.unparsed("name", rawName)));
                return;
            }
            try {
                action.accept(found.get());
            } catch (RuntimeException e) {
                ctx.logger().error("Inventory snapshot action for {} failed", found.get().uuid(), e);
                reply(sender, "failed");
            }
        });
    }

    private SnapshotStore.Draft draft(PlayerRef ref, CommandSender creator, String label, SnapshotReason reason,
            ItemStackSnapshots.Captured captured) {
        return new SnapshotStore.Draft(ref.uuid(), ref.name(), SnapshotCodec.FORMAT, System.currentTimeMillis(),
                creatorId(creator), creatorName(creator), label, reason, captured.stacks(), captured.bytes());
    }

    private void prune(UUID target) {
        InventoriesConfig current = config.get();
        long cutoff = InventoriesConfig.cutoff(current, System.currentTimeMillis());
        int keep = InventoriesConfig.retentionCount(current);
        store.prune(target, keep, cutoff).whenComplete((removed, error) -> {
            if (error != null) {
                ctx.logger().warn("Applying the snapshot retention of {} failed", target, error);
            }
        });
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        onSender(recipient, () -> ctx.messages().send(recipient, "inventories." + key, tags));
    }

    private void notifyTarget(Player target, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        ctx.scheduler().entity(target, () -> {
            if (!cancelled) {
                ctx.messages().send(target, "inventories." + key, tags);
            }
        }, null);
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (cancelled) {
            return;
        }
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    work.run();
                }
            }, null);
        } else {
            ctx.scheduler().global(() -> {
                if (!cancelled) {
                    work.run();
                }
            });
        }
    }

    private void failed(CommandSender sender, String operation, Throwable error) {
        ctx.logger().error("{} failed", operation, error);
        reply(sender, "failed");
    }

    private static boolean retired(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Scheduler.EntityRetiredException) {
                return true;
            }
        }
        return false;
    }

    private static boolean formatFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ItemStackSnapshots.SnapshotFailure) {
                return true;
            }
        }
        return false;
    }

    private static UUID creatorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private static String creatorName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Console";
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        confirmations.clear(actor(event.getPlayer()));
    }

    @Override
    public void cancel() {
        cancelled = true;
        confirmations.clearAll();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
