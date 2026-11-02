package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Delivers the stacks an administrator is owed from offline edits that never reached their target, as soon as
 * that administrator joins. Reading stays on the I/O executor and the items are only ever put into the player's
 * inventory on their own thread; anything that does not fit is dropped at their feet, never discarded.
 *
 * <p>A row is deleted only after its items were given, so a storage failure can at worst repeat a return, never
 * lose it.
 */
final class PendingReturnApplier implements Listener, TaskHandle {
    private record Payload(PendingReturnStore.Row row, List<ItemStack> items) {}

    private final ModuleContext ctx;
    private final PendingReturnStore store;
    private final java.util.Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> delivering = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    PendingReturnApplier(ModuleContext ctx, PendingReturnStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (cancelled || !loading.add(uuid)) {
            return;
        }
        store.pending(uuid).whenComplete((rows, error) -> {
            if (error != null) {
                loading.remove(uuid);
                ctx.logger().warn("Reading the item returns of {} failed; they stay queued", uuid, error);
                return;
            }
            if (rows.isEmpty() || cancelled) {
                loading.remove(uuid);
                return;
            }
            if (!delivering.add(uuid)) {
                loading.remove(uuid);
                return;
            }
            ctx.scheduler().supplyIo(() -> decode(rows)).whenComplete((payloads, decodeError) -> {
                if (decodeError != null) {
                    delivering.remove(uuid);
                    loading.remove(uuid);
                    ctx.logger().error("Reading the item returns of {} failed; they stay queued", uuid,
                            decodeError);
                    return;
                }
                try {
                    ctx.scheduler().entity(player, () -> deliver(player, payloads), () -> {
                        delivering.remove(uuid);
                        loading.remove(uuid);
                    });
                } catch (RuntimeException e) {
                    delivering.remove(uuid);
                    loading.remove(uuid);
                    ctx.logger().error("Scheduling the item returns of {} failed; they stay queued", uuid, e);
                }
            });
        });
    }

    /** Must run on the player's own thread. */
    private void deliver(Player player, List<Payload> payloads) {
        delivering.remove(player.getUniqueId());
        loading.remove(player.getUniqueId());
        if (cancelled) {
            return;
        }
        int stacks = 0;
        for (Payload payload : payloads) {
            try {
                for (ItemStack stack : ItemStackSnapshots.copies(payload.items())) {
                    for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                        if (!leftover.isEmpty()) {
                            player.getWorld().dropItem(player.getLocation(), leftover);
                        }
                    }
                }
                stacks += payload.items().size();
            } catch (RuntimeException e) {
                ctx.logger().error("Returning the items of return #{} to {} failed; the row stays queued",
                        payload.row().id(), player.getUniqueId(), e);
                continue;
            }
            store.delete(payload.row().id()).whenComplete((deleted, error) -> {
                if (error != null) {
                    ctx.logger().warn("Could not delete the delivered item return #{} of {}; it may be repeated",
                            payload.row().id(), player.getUniqueId(), error);
                } else if (!Boolean.TRUE.equals(deleted)) {
                    ctx.logger().info("Item return #{} of {} was already delivered", payload.row().id(),
                            player.getUniqueId());
                }
            });
        }
        if (stacks > 0) {
            ctx.messages().send(player, "inventories.refund-delivered", Msg.number("stacks", stacks));
            ctx.logger().info("Returned {} stack(s) of queued offline edit items to {}", stacks,
                    player.getUniqueId());
        }
    }

    /** Decodes every row off the game thread; an unreadable row stays queued and is logged. */
    private List<Payload> decode(List<PendingReturnStore.Row> rows) {
        List<Payload> payloads = new ArrayList<>(rows.size());
        for (PendingReturnStore.Row row : rows) {
            try {
                payloads.add(new Payload(row, ItemStackDelta.decode(row.items())));
            } catch (RuntimeException e) {
                ctx.logger().error("Item return #{} of {} cannot be read and stays queued", row.id(),
                        row.admin(), e);
            }
        }
        return List.copyOf(payloads);
    }

    @Override
    public void cancel() {
        cancelled = true;
        loading.clear();
        delivering.clear();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
