package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.api.inventories.InventorySnapshotService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The published {@link InventorySnapshotService}. Reads decode on the I/O executor; scheduling validates the
 * base snapshot, stores the edited copy and only then caches it for the next join, so the join path is pure
 * memory. A snapshot of a newer format is refused instead of being decoded with the wrong layout.
 */
final class SnapshotService implements InventorySnapshotService {
    private final ModuleContext ctx;
    private final SnapshotStore store;
    private final PendingEditStore pendingStore;
    private final PendingEditApplier applier;

    SnapshotService(ModuleContext ctx, SnapshotStore store, PendingEditStore pendingStore,
            PendingEditApplier applier) {
        this.ctx = ctx;
        this.store = store;
        this.pendingStore = pendingStore;
        this.applier = applier;
    }

    @Override
    public CompletableFuture<Optional<Snapshot>> latest(UUID player, @Nullable String reason) {
        if (player == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        SnapshotReason filter = null;
        if (reason != null) {
            filter = SnapshotReason.byKey(reason);
            if (filter == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }
        SnapshotReason wanted = filter;
        return store.latest(player, wanted).thenApply(found -> found.flatMap(this::toSnapshot));
    }

    @Override
    public CompletableFuture<Optional<PendingEdit>> schedulePendingEdit(UUID player, long baseSnapshotId,
            Contents contents) {
        return schedulePendingEdit(player, baseSnapshotId, contents, null, null);
    }

    @Override
    public CompletableFuture<Optional<PendingEdit>> schedulePendingEdit(UUID player, long baseSnapshotId,
            Contents contents, @Nullable UUID editor, @Nullable String editorName) {
        if (player == null || contents == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // An online player must be edited live; a pending edit would only apply on their next join.
        return ctx.scheduler().supplyGlobal(() -> Bukkit.getPlayer(player)).thenCompose(online -> {
            if (online != null) {
                return CompletableFuture.completedFuture(Optional.<PendingEdit>empty());
            }
            return store.get(player, baseSnapshotId).thenCompose(base -> {
                if (base.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.<PendingEdit>empty());
                }
                SnapshotStore.Stored stored = base.get();
                if (stored.meta().format() > SnapshotCodec.FORMAT) {
                    ctx.logger().warn("Refusing a pending edit of {} based on snapshot #{}: format {} is newer than {}",
                            player, baseSnapshotId, stored.meta().format(), SnapshotCodec.FORMAT);
                    return CompletableFuture.completedFuture(Optional.<PendingEdit>empty());
                }
                String creatorName = sanitizeEditorName(editorName);
                long created = System.currentTimeMillis();
                return ctx.scheduler().supplyIo(() -> prepared(contents, stored)).thenCompose(prepared -> {
                    PendingEditStore.Draft draft = new PendingEditStore.Draft(player, stored.meta().playerName(),
                            baseSnapshotId, SnapshotCodec.FORMAT, created, editor, creatorName,
                            prepared.edited().stacks(), prepared.edited().bytes(), prepared.deltaStacks(),
                            prepared.deltaBytes());
                    return pendingStore.insert(draft).thenCompose(inserted -> {
                        PendingEditStore.Row row = new PendingEditStore.Row(inserted.id(), player,
                                stored.meta().playerName(), baseSnapshotId, SnapshotCodec.FORMAT, created, editor,
                                creatorName, PendingEditStore.Status.PENDING, null, prepared.edited().stacks(),
                                prepared.edited().bytes(), prepared.deltaStacks(), prepared.deltaBytes());
                        // The previous edit never reached the target, so its editor still owns those stacks.
                        applier.refundSuperseded(inserted.superseded());
                        return applier.remember(row)
                                .thenApply(unused -> Optional.of(new PendingEdit(inserted.id(), player,
                                        baseSnapshotId, created)));
                    });
                });
            });
        });
    }

    private Optional<Snapshot> toSnapshot(SnapshotStore.Stored stored) {
        if (stored.meta().format() > SnapshotCodec.FORMAT) {
            ctx.logger().warn("Snapshot #{} of {} ({}) uses format {}, but this server supports {}",
                    stored.meta().id(), stored.meta().playerName(), stored.meta().target(),
                    stored.meta().format(), SnapshotCodec.FORMAT);
            return Optional.empty();
        }
        try {
            SnapshotCodec.Contents<ItemStack> contents = ItemStackSnapshots.decode(stored.items());
            return Optional.of(new Snapshot(stored.meta().id(), stored.meta().target(),
                    stored.meta().playerName(), stored.meta().created(), stored.meta().reason().key(),
                    contents.inventory(), contents.enderChest()));
        } catch (SnapshotCodec.SnapshotFormatException | RuntimeException e) {
            ctx.logger().warn("Snapshot #{} of {} ({}) cannot be read: {}", stored.meta().id(),
                    stored.meta().playerName(), stored.meta().target(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Serializes off the game thread; the item lists are already private copies. */
    private record Prepared(ItemStackSnapshots.Captured edited, int deltaStacks, byte[] deltaBytes) {}

    /**
     * Serializes the edited copy and works out which stacks the editor contributed on top of the base snapshot.
     * A base that cannot be decoded keeps the edit usable but logs that a rejection cannot refund the editor.
     */
    private Prepared prepared(Contents contents, SnapshotStore.Stored base)
            throws SnapshotCodec.SnapshotFormatException {
        ItemStackSnapshots.Captured edited = captured(contents);
        List<ItemStack> added;
        try {
            SnapshotCodec.Contents<ItemStack> decoded = ItemStackSnapshots.decode(base.items());
            added = ItemStackDelta.added(combined(decoded), combined(contents));
        } catch (SnapshotCodec.SnapshotFormatException | RuntimeException e) {
            ctx.logger().warn("Base snapshot #{} of {} cannot be read, so a rejected offline edit of {} "
                    + "cannot return the items its editor added: {}", base.meta().id(), base.meta().playerName(),
                    base.meta().target(), e.getMessage());
            added = List.of();
        }
        return new Prepared(edited, added.size(), ItemStackDelta.encode(added));
    }

    private static List<ItemStack> combined(SnapshotCodec.Contents<ItemStack> contents) {
        List<ItemStack> all = new java.util.ArrayList<>(contents.inventory().size() + contents.enderChest().size());
        all.addAll(contents.inventory());
        all.addAll(contents.enderChest());
        return all;
    }

    private static List<ItemStack> combined(Contents contents) {
        List<ItemStack> all = new java.util.ArrayList<>(contents.inventory().size()
                + contents.enderChest().size());
        all.addAll(contents.inventory());
        all.addAll(contents.enderChest());
        return all;
    }

    /** Serializes off the game thread; the item lists are already private copies. */
    private static ItemStackSnapshots.Captured captured(Contents contents) throws SnapshotCodec.SnapshotFormatException {
        List<ItemStack> inventory = contents.inventory();
        List<ItemStack> ender = contents.enderChest();
        byte[] bytes = SnapshotCodec.encode(new SnapshotCodec.Contents<>(inventory, ender),
                ItemStackSnapshots.CODEC);
        return new ItemStackSnapshots.Captured(bytes, ItemStackSnapshots.countStacks(inventory)
                + ItemStackSnapshots.countStacks(ender), inventory, ender);
    }

    private static String sanitizeEditorName(@Nullable String raw) {
        String cleaned = Labels.clean(raw, 64);
        return cleaned == null ? "Console" : cleaned;
    }
}
