package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Applies pending offline edits on the target's next join, at {@link EventPriority#LOWEST}, on the player's own
 * thread and before they can interact. The base snapshot and the edited copy are cached when the edit is
 * scheduled, so the join path itself never touches storage: the only database writes are the backup, the
 * resolved status and a rejected copy kept for the history, all asynchronous.
 *
 * <p>An edit is applied only while the live contents are still identical to the base snapshot
 * ({@link BackupGuard}). Otherwise it is discarded; the edited copy is stored as a
 * {@link SnapshotReason#PENDING_EDIT_REJECTED} snapshot so nothing is silently lost, and the stacks the editor
 * added are returned to them (now when they are online, otherwise on their next join). Staff are notified.
 */
final class PendingEditApplier implements Listener, TaskHandle {
    private static final String PERM_NOTIFY = "atrium.inventories.notify";

    /** The cached state needed to decide and apply an edit without blocking the join thread. */
    record Pending(long id, UUID target, String playerName, long baseSnapshot, UUID editor, String editorName,
            ItemStackSnapshots.Captured base, ItemStackSnapshots.Captured edit, int deltaStacks,
            byte[] deltaItems) {}

    /** The stacks one editor contributed to an edit that will never be applied. */
    private record Owed(UUID editor, String editorName, String targetName, int stacks, byte[] items,
            String reason) {}

    private final ModuleContext ctx;
    private final ConfigHandle<InventoriesConfig> config;
    private final SnapshotStore store;
    private final PendingEditStore pendingStore;
    private final PendingReturnStore returnStore;
    private final ConcurrentMap<UUID, Pending> cache = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> resolved = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    PendingEditApplier(ModuleContext ctx, ConfigHandle<InventoriesConfig> config, SnapshotStore store,
            PendingEditStore pendingStore, PendingReturnStore returnStore) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.pendingStore = pendingStore;
        this.returnStore = returnStore;
    }

    /** Called by the service once the row is stored; the player is offline, so no player object is needed. */
    CompletableFuture<Void> remember(PendingEditStore.Row row) {
        return loadPending(row).thenAccept(pending -> pending.ifPresent(p -> {
            if (p.base() == null) {
                discard(p, PendingEditPolicy.Decision.DISCARD_MISSING_BASE);
            } else {
                cache.put(p.target(), p);
            }
        })).whenComplete((unused, error) -> {
            if (error != null) {
                ctx.logger().warn("Caching pending offline edit #{} of {} failed", row.id(), row.target(), error);
            }
        });
    }

    /** Loads edits that survived a restart; runs on enable, before players can join. */
    void preload() {
        pendingStore.unresolved().thenCompose(rows -> {
            List<CompletableFuture<Void>> futures = rows.stream()
                    .map(row -> loadPending(row).thenAccept(pending -> pending.ifPresent(p -> {
                        if (p.base() == null) {
                            discard(p, PendingEditPolicy.Decision.DISCARD_MISSING_BASE);
                        } else {
                            cache.put(p.target(), p);
                        }
                    })).exceptionally(error -> {
                        ctx.logger().warn("Loading pending offline edit #{} of {} failed", row.id(), row.target(),
                                error);
                        return null;
                    })).toList();
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        }).whenComplete((unused, error) -> {
            if (error != null) {
                ctx.logger().warn("Preloading pending offline edits failed", error);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Pending cached = cache.remove(player.getUniqueId());
        if (cached != null) {
            apply(player, cached);
            return;
        }
        // Fallback for a restart or a join racing the cache write: one indexed lookup, never on the join path.
        if (cancelled || !loading.add(player.getUniqueId())) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pendingStore.latestUnresolved(uuid).thenCompose(found -> found.isEmpty()
                        || resolved.contains(found.get().id())
                ? CompletableFuture.completedFuture(Optional.<Pending>empty())
                : loadPending(found.get())).whenComplete((pending, error) -> {
                    loading.remove(uuid);
                    if (error != null) {
                        ctx.logger().warn("Reading the pending offline edit of {} failed", uuid, error);
                        return;
                    }
                    pending.ifPresent(p -> ctx.scheduler().entity(player, () -> apply(player, p), null));
                });
    }

    /** Runs on the target's thread, before they can move or interact. */
    private void apply(Player player, Pending pending) {
        if (cancelled || !player.isOnline()) {
            cache.put(pending.target(), pending);
            return;
        }
        ItemStackSnapshots.Captured now;
        try {
            now = ItemStackSnapshots.capture(player);
        } catch (RuntimeException e) {
            ctx.logger().error("Capturing the inventory of {} before a pending edit failed", pending.target(), e);
            cache.put(pending.target(), pending);
            return;
        }
        // Section by section, so a base captured with other slot counts (an older format, a resized ender chest)
        // still matches when the extra slots are empty.
        boolean matches = pending.base() != null && ItemStackSnapshots.matches(pending.base(), now);
        PendingEditPolicy.Decision decision = PendingEditPolicy.decide(pending.base() != null, matches);
        if (decision == PendingEditPolicy.Decision.APPLY) {
            applyNow(player, pending, now);
        } else {
            discard(pending, decision);
        }
    }

    private void applyNow(Player player, Pending pending, ItemStackSnapshots.Captured before) {
        // The items the editor added now belong to the target, so nothing is returned to the editor here.
        // The backup insert starts before the replacement; its payload is already captured, so a slow write can
        // never describe the edited contents instead of the original ones.
        SnapshotStore.Draft backup = new SnapshotStore.Draft(pending.target(), pending.playerName(),
                SnapshotCodec.FORMAT, System.currentTimeMillis(), pending.editor(), pending.editorName(),
                "before offline edit", SnapshotReason.PRE_PENDING_EDIT, before.stacks(), before.bytes());
        store.insert(backup).whenComplete((id, error) -> {
            if (error != null) {
                ctx.logger().error("Backing up {} before applying pending edit #{} failed", pending.target(),
                        pending.id(), error);
            } else {
                prune(pending.target());
            }
        });
        ItemStackSnapshots.Applied applied;
        try {
            applied = ItemStackSnapshots.apply(player, new SnapshotCodec.Contents<>(
                    pending.edit().inventory(), pending.edit().ender()), false);
        } catch (RuntimeException e) {
            ctx.logger().error("Applying pending offline edit #{} to {} failed", pending.id(), pending.target(), e);
            cache.put(pending.target(), pending);
            return;
        }
        resolve(pending, PendingEditStore.Status.APPLIED, "applied");
        ctx.messages().send(player, "inventories.pending-applied-target");
        if (applied.overflow() > 0) {
            ctx.logger().info("Pending offline edit #{} of {} had {} stack(s) beyond the live slot counts: moved into "
                    + "free inventory space, {} dropped at the player's feet", pending.id(), pending.target(),
                    applied.overflow(), applied.dropped());
        }
        if (applied.dropped() > 0) {
            ctx.messages().send(player, "inventories.overflow-dropped-target",
                    Msg.number("dropped", applied.dropped()));
        }
        ctx.logger().info("Applied pending offline edit #{} to {} (base snapshot #{})", pending.id(),
                pending.target(), pending.baseSnapshot());
    }

    /**
     * Keeps the rejected copy in the snapshot history (so nothing an administrator prepared disappears), returns
     * the stacks the editor added, resolves the row and tells the online staff. Runs without the target player,
     * because at preload the target may have been offline for a long time.
     */
    private void discard(Pending pending, PendingEditPolicy.Decision decision) {
        SnapshotStore.Draft history = new SnapshotStore.Draft(pending.target(), pending.playerName(),
                SnapshotCodec.FORMAT, System.currentTimeMillis(), pending.editor(), pending.editorName(),
                "rejected offline edit", SnapshotReason.PENDING_EDIT_REJECTED, pending.edit().stacks(),
                pending.edit().bytes());
        store.insert(history).whenComplete((id, error) -> {
            if (error != null) {
                ctx.logger().error("Keeping the rejected offline edit #{} of {} failed", pending.id(),
                        pending.target(), error);
            } else {
                prune(pending.target());
            }
        });
        resolve(pending, PendingEditStore.Status.DISCARDED, PendingEditPolicy.detail(decision));
        ctx.logger().info("Discarded pending offline edit #{} of {} ({})", pending.id(), pending.target(),
                PendingEditPolicy.detail(decision));
        refundEdit(new Owed(pending.editor(), pending.editorName(), pending.playerName(), pending.deltaStacks(),
                pending.deltaItems(), PendingEditPolicy.detail(decision)), decision);
        notifyStaff(decision == PendingEditPolicy.Decision.DISCARD_MISSING_BASE
                        ? "inventories.pending-discarded-missing"
                        : "inventories.pending-discarded-changed",
                Msg.unparsed("player", pending.playerName()), Msg.number("id", pending.id()),
                Msg.number("base", pending.baseSnapshot()));
    }

    /**
     * Returns the stacks of edits that a newer edit replaced. They were taken from the editor when the edit was
     * stored and can never reach the target, so they belong back to the editor.
     */
    void refundSuperseded(List<PendingEditStore.Superseded> superseded) {
        for (PendingEditStore.Superseded row : superseded) {
            refundEdit(new Owed(row.editor(), row.editorName(), row.playerName(), row.deltaStacks(),
                    row.deltaItems(), "newer-edit"), null);
        }
    }

    /**
     * Decides where the contributed stacks go: back to an online editor on their own thread, or into
     * {@code inventories_returns} until their next join. An applied edit never calls this.
     */
    private void refundEdit(Owed owed, PendingEditPolicy.@Nullable Decision decision) {
        if (owed.editor() == null || owed.stacks() <= 0 || owed.items() == null || owed.items().length == 0) {
            return;
        }
        UUID editor = owed.editor();
        ctx.scheduler().supplyGlobal(() -> Bukkit.getPlayer(editor)).whenComplete((online, error) -> {
            if (error != null) {
                ctx.logger().warn("Looking up the editor {} of a rejected offline edit failed; the items were "
                        + "queued for their next join", editor, error);
                queue(owed);
                return;
            }
            RefundPlan.Plan plan = decision == null
                    ? RefundPlan.forRejection(editor, online != null, owed.stacks())
                    : RefundPlan.forDecision(decision, editor, online != null, owed.stacks());
            switch (plan.delivery()) {
                case REFUND_NOW -> {
                    if (online == null) {
                        queue(owed);
                    } else {
                        deliverNow(online, owed);
                    }
                }
                case QUEUE_OFFLINE -> queue(owed);
                case NONE -> {
                }
            }
        });
    }

    /** Gives the stacks on the editor's own thread; overflow is dropped at their feet, never deleted. */
    private void deliverNow(Player editor, Owed owed) {
        ctx.scheduler().supplyIo(() -> ItemStackDelta.decode(owed.items())).whenComplete((items, error) -> {
            if (error != null) {
                ctx.logger().error("The contributed stacks owed to {} cannot be read; the payload stays queued",
                        owed.editor(), error);
                queue(owed);
                return;
            }
            try {
                ctx.scheduler().entity(editor, () -> {
                    try {
                        if (!editor.isOnline()) {
                            queue(owed);
                            return;
                        }
                        give(editor, items);
                        ctx.messages().send(editor, "inventories.refund-returned",
                                Msg.number("stacks", items.size()),
                                Msg.unparsed("player", owed.targetName()));
                        ctx.logger().info("Returned {} stack(s) to {} after their offline edit of {} was discarded",
                                items.size(), owed.editor(), owed.targetName());
                    } catch (RuntimeException e) {
                        ctx.logger().error("Returning the contributed stacks to {} failed; they were queued for "
                                + "their next join", owed.editor(), e);
                        queue(owed);
                    }
                }, () -> queue(owed));
            } catch (RuntimeException e) {
                ctx.logger().error("Scheduling the item return for {} failed; it was queued for their next join",
                        owed.editor(), e);
                queue(owed);
            }
        });
    }

    /** Keeps the payload until the editor joins; the row is never purged, so nothing is lost. */
    private void queue(Owed owed) {
        PendingReturnStore.Draft draft = new PendingReturnStore.Draft(owed.editor(), owed.editorName(),
                System.currentTimeMillis(), PendingReturnStore.reason(owed.reason()), owed.targetName(),
                owed.stacks(), owed.items());
        returnStore.insert(draft).whenComplete((id, error) -> {
            if (error != null) {
                ctx.logger().error("Could not store the items owed to {} (offline edit of {}); payload lost",
                        owed.editor(), owed.targetName(), error);
            } else {
                ctx.logger().info("Queued {} stack(s) for {} to receive on their next join (return #{})",
                        owed.stacks(), owed.editor(), id);
            }
        });
    }

    private static void give(Player player, List<ItemStack> items) {
        for (ItemStack stack : ItemStackSnapshots.copies(items)) {
            for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), leftover);
                }
            }
        }
    }

    private void resolve(Pending pending, PendingEditStore.Status status, String detail) {
        resolved.add(pending.id());
        pendingStore.resolve(pending.id(), status, detail).whenComplete((changed, error) -> {
            if (error != null) {
                ctx.logger().warn("Resolving pending offline edit #{} failed", pending.id(), error);
            } else if (!Boolean.TRUE.equals(changed)) {
                ctx.logger().debug("Pending offline edit #{} was already resolved", pending.id());
            }
        });
    }

    /** Builds the cached form; resolves a corrupt row instead of breaking a join. */
    private CompletableFuture<Optional<Pending>> loadPending(PendingEditStore.Row row) {
        ItemStackSnapshots.Captured edit;
        try {
            SnapshotCodec.Contents<ItemStack> contents = ItemStackSnapshots.decode(row.items());
            edit = new ItemStackSnapshots.Captured(row.items(), row.stacks(), contents.inventory(),
                    contents.enderChest());
        } catch (SnapshotCodec.SnapshotFormatException | RuntimeException e) {
            ctx.logger().warn("Pending offline edit #{} of {} is unreadable and was discarded: {}", row.id(),
                    row.target(), e.getMessage());
            pendingStore.resolve(row.id(), PendingEditStore.Status.DISCARDED, "corrupt").whenComplete((changed, error) -> {
                if (error != null) {
                    ctx.logger().warn("Resolving the corrupt pending edit #{} failed", row.id(), error);
                }
            });
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return store.get(row.target(), row.baseSnapshot()).thenApply(base -> {
            ItemStackSnapshots.Captured captured = null;
            if (base.isPresent()) {
                try {
                    captured = captured(base.get());
                } catch (RuntimeException e) {
                    ctx.logger().warn("Base snapshot #{} of pending offline edit #{} cannot be read: {}",
                            row.baseSnapshot(), row.id(), e.getMessage());
                }
            }
            return Optional.of(new Pending(row.id(), row.target(), row.playerName(), row.baseSnapshot(),
                    row.editor(), row.editorName(), captured, edit, row.deltaStacks(), row.deltaItems()));
        });
    }

    private static ItemStackSnapshots.Captured captured(SnapshotStore.Stored stored) {
        if (stored.meta().format() > SnapshotCodec.FORMAT) {
            throw new IllegalStateException("snapshot #" + stored.meta().id() + " uses unsupported format "
                    + stored.meta().format());
        }
        try {
            SnapshotCodec.Contents<ItemStack> contents = ItemStackSnapshots.decode(stored.items());
            return new ItemStackSnapshots.Captured(stored.items(), stored.meta().stacks(), contents.inventory(),
                    contents.enderChest());
        } catch (SnapshotCodec.SnapshotFormatException e) {
            throw new IllegalStateException("snapshot #" + stored.meta().id() + " cannot be decoded: "
                    + e.getMessage(), e);
        }
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

    private void notifyStaff(String key, TagResolver... tags) {
        // May be called from a join thread or the storage executor; the player list belongs to the global thread.
        if (!Bukkit.isGlobalTickThread()) {
            ctx.scheduler().global(() -> notifyStaff(key, tags));
            return;
        }
        for (Player staff : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!staff.hasPermission(PERM_NOTIFY)) {
                continue;
            }
            ctx.scheduler().entity(staff, () -> {
                if (!cancelled) {
                    ctx.messages().send(staff, key, tags);
                }
            }, null);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        cache.clear();
        loading.clear();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
