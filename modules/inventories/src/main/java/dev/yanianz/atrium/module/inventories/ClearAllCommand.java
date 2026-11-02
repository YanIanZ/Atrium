package dev.yanianz.atrium.module.inventories;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

/**
 * {@code /clearall <player|all> [-s]}: clears the inventory, armour, offhand and ender chest of the target(s).
 *
 * <p>The snapshot is always written first, on the target's own thread, and the clear only runs once that insert
 * completed and while the live contents still equal the snapshot (bounded re-backup, see {@link BackupGuard}).
 * Clearing another player or every online player must be confirmed by repeating the command. An offline player is
 * refused with a clickable list of who is online, because Paper cannot edit an unloaded inventory.
 */
final class ClearAllCommand implements TaskHandle {
    private static final String PERM = "atrium.clearall";
    private static final String PERM_OTHERS = "atrium.clearall.others";
    private static final String PERM_ALL = "atrium.clearall.all";
    private static final String PERM_SILENT = "atrium.inventories.silent";
    private static final int MAX_SUGGESTED_TARGETS = 8;

    private final ModuleContext ctx;
    private final ConfigHandle<InventoriesConfig> config;
    private final SnapshotStore store;
    private final Confirmations confirmations = new Confirmations();
    private volatile boolean cancelled;

    ClearAllCommand(ModuleContext ctx, ConfigHandle<InventoriesConfig> config, SnapshotStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
    }

    void register() {
        ctx.track(this);
        ctx.permission(PERM, org.bukkit.permissions.PermissionDefault.OP,
                "Back up and clear your own inventory, armour, offhand and ender chest");
        ctx.permission(PERM_OTHERS, org.bukkit.permissions.PermissionDefault.OP,
                "Clear the inventory and ender chest of another player");
        ctx.permission(PERM_ALL, org.bukkit.permissions.PermissionDefault.OP,
                "Clear the inventory and ender chest of every online player");

        LiteralArgumentBuilder<CommandSourceStack> clearAll = Cmds.literal("clearall", PERM);
        clearAll.executes(this::usage);
        clearAll.then(Cmds.literal("-s", PERM_SILENT).executes(this::usage)
                .then(targetChain(true))
                .then(allChain(true)));
        clearAll.then(targetChain(false));
        clearAll.then(allChain(false));
        ctx.command(CommandSpec.of(clearAll.build(),
                "Back up and clear the inventory, armour, offhand and ender chest of a player or everyone"));
    }

    private ArgumentBuilder<CommandSourceStack, ?> targetChain(boolean silent) {
        var player = Commands.argument("player", StringArgumentType.word())
                .suggests(Cmds.knownPlayers(ctx.users()));
        if (silent) {
            return player.executes(c -> single(c, true));
        }
        player.executes(c -> single(c, false));
        player.then(Cmds.literal("-s", PERM_SILENT).executes(c -> single(c, true)));
        return player;
    }

    private ArgumentBuilder<CommandSourceStack, ?> allChain(boolean silent) {
        LiteralArgumentBuilder<CommandSourceStack> all = Cmds.literal("all", PERM_ALL).executes(c -> all(c, silent));
        if (!silent) {
            all.then(Cmds.literal("-s", PERM_SILENT).executes(c -> all(c, true)));
        }
        return all;
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "inventories.usage-clearall");
        return Cmds.OK;
    }

    // ---- one named player ----

    private int single(CommandContext<CommandSourceStack> c, boolean silentRequested) {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        boolean silent = ClearAllPolicy.silent(silentRequested, sender.hasPermission(PERM_SILENT));
        resolve(sender, rawName, ref -> {
            Player target = Bukkit.getPlayer(ref.uuid());
            boolean self = sender instanceof Player player && player.getUniqueId().equals(ref.uuid());
            ClearAllPolicy.Kind kind = ClearAllPolicy.kind(self, false);
            ClearAllPolicy.Refusal refusal = ClearAllPolicy.check(kind,
                    sender.hasPermission(PERM_OTHERS), sender.hasPermission(PERM_ALL), target != null);
            if (refusal != ClearAllPolicy.Refusal.NONE) {
                refuse(sender, refusal, ref.name(), rawName);
                return;
            }
            int seconds = InventoriesConfig.confirmationSeconds(config.get());
            String key = "clearall|" + ref.uuid();
            if (ClearAllPolicy.needsConfirmation(kind)
                    && !confirmations.confirm(actor(sender), key, System.currentTimeMillis(),
                            seconds * 1000L)) {
                reply(sender, "confirm-clearall-one", Msg.unparsed("player", ref.name()),
                        Msg.number("seconds", seconds));
                return;
            }
            clearOne(sender, target, ref, silent, 1);
        });
        return Cmds.OK;
    }

    // ---- every online player ----

    private int all(CommandContext<CommandSourceStack> c, boolean silentRequested) {
        CommandSender sender = c.getSource().getSender();
        boolean silent = ClearAllPolicy.silent(silentRequested, sender.hasPermission(PERM_SILENT));
        ClearAllPolicy.Refusal refusal = ClearAllPolicy.check(ClearAllPolicy.Kind.ALL,
                sender.hasPermission(PERM_OTHERS), sender.hasPermission(PERM_ALL), true);
        if (refusal == ClearAllPolicy.Refusal.ALL_PERMISSION) {
            // The literal already requires the permission; keep a message in case the tree changes.
            reply(sender, "no-permission-all", Msg.unparsed("permission", PERM_ALL));
            return Cmds.OK;
        }
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) {
            reply(sender, "clearall-none-online");
            return Cmds.OK;
        }
        int seconds = InventoriesConfig.confirmationSeconds(config.get());
        if (!confirmations.confirm(actor(sender), "clearall|all", System.currentTimeMillis(), seconds * 1000L)) {
            reply(sender, "confirm-clearall-all", Msg.number("players", targets.size()),
                    Msg.number("seconds", seconds));
            return Cmds.OK;
        }
        AtomicInteger cleared = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<CompletableFuture<Boolean>> running = new ArrayList<>(targets.size());
        for (Player target : targets) {
            PlayerRef ref = new PlayerRef(target.getUniqueId(), target.getName());
            running.add(clearOne(sender, target, ref, silent, 1).thenApply(success -> {
                (success ? cleared : failed).incrementAndGet();
                return success;
            }));
        }
        CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).whenComplete((unused, error) -> {
            if (error != null) {
                ctx.logger().warn("Clearing every online player finished with an error", error);
            }
            if (!silent) {
                reply(sender, "clearall-all-done", Msg.number("cleared", cleared.get()),
                        Msg.number("failed", failed.get()));
            }
        });
        return Cmds.OK;
    }

    // ---- the actual backup + clear ----

    private CompletableFuture<Boolean> clearOne(CommandSender sender, Player target, PlayerRef ref, boolean silent,
            int attempt) {
        if (cancelled) {
            return CompletableFuture.completedFuture(false);
        }
        return ctx.scheduler().supplyOnEntity(target, () -> {
            ItemStackSnapshots.Captured captured = ItemStackSnapshots.capture(target);
            // Nothing to back up and nothing to clear; avoids a pointless row.
            return captured.stacks() == 0 ? null : captured;
        }).thenCompose(captured -> {
            if (captured == null) {
                reply(sender, "clearall-empty", Msg.unparsed("player", ref.name()));
                return CompletableFuture.completedFuture(false);
            }
            SnapshotStore.Draft draft = new SnapshotStore.Draft(ref.uuid(), ref.name(), SnapshotCodec.FORMAT,
                    System.currentTimeMillis(), creatorId(sender), creatorName(sender), null,
                    SnapshotReason.CLEAR_ALL, captured.stacks(), captured.bytes());
            return store.insert(draft)
                    .thenCompose(backupId -> applyClear(sender, target, ref, captured, silent, attempt, backupId));
        }).exceptionally(error -> {
            if (retired(error)) {
                reply(sender, "target-left");
            } else {
                ctx.logger().error("Clearing the inventory of {} ({}) failed", ref.name(), ref.uuid(), error);
                reply(sender, "failed");
            }
            return false;
        });
    }

    private CompletableFuture<Boolean> applyClear(CommandSender sender, Player target, PlayerRef ref,
            ItemStackSnapshots.Captured captured, boolean silent, int attempt, long backupId) {
        return ctx.scheduler().supplyOnEntity(target, () -> {
            // The backup must describe exactly what is cleared; items picked up meanwhile trigger a fresh backup.
            if (!ItemStackSnapshots.unchanged(target, captured, false)) {
                return Boolean.FALSE;
            }
            clear(target);
            return Boolean.TRUE;
        }).thenCompose(cleared -> {
            if (!cleared) {
                discardStaleBackup(ref, backupId);
                if (BackupGuard.decide(false, attempt) == BackupGuard.Decision.REBACKUP) {
                    ctx.logger().info("Contents of {} changed while backup #{} was written; backing up again",
                            ref.uuid(), backupId);
                    return clearOne(sender, target, ref, silent, attempt + 1);
                }
                reply(sender, "changed-retry", Msg.unparsed("player", ref.name()));
                return CompletableFuture.completedFuture(false);
            }
            prune(ref.uuid());
            ctx.logger().info("Cleared the inventory and ender chest of {} after backup #{}", ref.uuid(), backupId);
            if (!silent) {
                reply(sender, "clearall-cleared", Msg.unparsed("player", ref.name()), Msg.number("backup", backupId));
                notifyTarget(target, "clearall-cleared-target");
            }
            return CompletableFuture.completedFuture(true);
        }).exceptionally(error -> {
            if (retired(error)) {
                reply(sender, "target-left");
            } else {
                ctx.logger().error("Clearing the inventory of {} ({}) failed", ref.name(), ref.uuid(), error);
                reply(sender, "failed");
            }
            return false;
        });
    }

    /** Runs on the target's thread. Clears storage, armour, offhand and the ender chest. */
    private static void clear(Player target) {
        PlayerInventory inventory = target.getInventory();
        inventory.clear();
        inventory.setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        inventory.setItemInOffHand(null);
        target.getEnderChest().clear();
        target.updateInventory();
    }

    // ---- helpers ----

    private void refuse(CommandSender sender, ClearAllPolicy.Refusal refusal, String name, String typedName) {
        switch (refusal) {
            case OTHERS_PERMISSION -> onSender(sender, () -> ctx.messages().send(sender,
                    "core.no-permission-others", Msg.unparsed("permission", PERM_OTHERS)));
            case ALL_PERMISSION -> reply(sender, "no-permission-all", Msg.unparsed("permission", PERM_ALL));
            case OFFLINE -> onSender(sender, () -> {
                ctx.messages().send(sender, "inventories.must-be-online", Msg.unparsed("player", name));
                Component online = onlineTargets(sender, typedName);
                if (online == null) {
                    ctx.messages().send(sender, "inventories.clearall-online-none");
                } else {
                    ctx.messages().send(sender, "inventories.clearall-online", Msg.component("online", online));
                }
            });
            case NONE -> { }
        }
    }

    /**
     * Clickable online player names: the closest match to what the sender typed comes first. Lang files never parse
     * these names as MiniMessage, they are built as plain components.
     */
    private Component onlineTargets(CommandSender sender, String typedName) {
        String typed = typedName == null ? "" : typedName.toLowerCase(java.util.Locale.ROOT);
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort((first, second) -> {
            boolean firstMatch = first.getName().toLowerCase(java.util.Locale.ROOT).startsWith(typed);
            boolean secondMatch = second.getName().toLowerCase(java.util.Locale.ROOT).startsWith(typed);
            if (firstMatch != secondMatch) {
                return firstMatch ? -1 : 1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName());
        });
        Component line = Component.empty();
        int shown = Math.min(online.size(), MAX_SUGGESTED_TARGETS);
        for (int index = 0; index < shown; index++) {
            Player player = online.get(index);
            if (index > 0) {
                line = line.append(Component.text(", "));
            }
            line = line.append(Component.text(player.getName())
                    .clickEvent(ClickEvent.suggestCommand("/clearall " + player.getName()))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender,
                            "inventories.clearall-online-hover", Msg.unparsed("player", player.getName())))));
        }
        return shown == 0 ? null : line;
    }

    private void resolve(CommandSender sender, String rawName, Consumer<PlayerRef> action) {
        ctx.users().resolveAccount(rawName, false).whenComplete((found, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                ctx.logger().warn("Resolving player {} for /clearall failed", rawName, error);
                reply(sender, "failed");
                return;
            }
            if (found == null || found.isEmpty()) {
                onSender(sender, () -> ctx.messages().send(sender, "core.user-not-found",
                        Msg.unparsed("name", rawName)));
                return;
            }
            try {
                action.accept(found.get());
            } catch (RuntimeException e) {
                ctx.logger().error("/clearall for {} failed", found.get().uuid(), e);
                reply(sender, "failed");
            }
        });
    }

    private void prune(UUID target) {
        InventoriesConfig current = config.get();
        store.prune(target, InventoriesConfig.retentionCount(current),
                InventoriesConfig.cutoff(current, System.currentTimeMillis())).whenComplete((removed, error) -> {
                    if (error != null) {
                        ctx.logger().warn("Applying the snapshot retention of {} failed", target, error);
                    }
                });
    }

    private void discardStaleBackup(PlayerRef ref, long id) {
        store.delete(ref.uuid(), id).whenComplete((deleted, error) -> {
            if (error != null) {
                ctx.logger().warn("Could not remove the outdated backup #{} of {}", id, ref.uuid(), error);
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

    private static boolean retired(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Scheduler.EntityRetiredException) {
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
