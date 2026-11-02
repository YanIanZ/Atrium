package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Hands items back to a player without ever losing or duplicating them: into the inventory, overflow dropped
 * at the player's feet, and when the player is offline, dead or unreachable, persisted as a pending return
 * that is delivered on the next join or respawn.
 */
final class ItemReturns {
    private final ModuleContext ctx;
    private final WorthStore store;

    ItemReturns(ModuleContext ctx, WorthStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    /** From any thread: hands the items back on the player's thread, or persists them. */
    void give(Player player, List<ItemStack> items) {
        List<ItemStack> copy = nonEmpty(items);
        if (copy.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ctx.scheduler().entity(player, () -> giveNow(player, copy), () -> persist(uuid, copy));
    }

    /** On the player's thread. */
    void giveNow(Player player, List<ItemStack> items) {
        List<ItemStack> copy = nonEmpty(items);
        if (copy.isEmpty()) {
            return;
        }
        if (!player.isOnline() || player.isDead() || !Bukkit.isOwnedByCurrentRegion(player)) {
            persist(player.getUniqueId(), copy);
            return;
        }
        var leftovers = player.getInventory().addItem(copy.toArray(ItemStack[]::new));
        for (ItemStack rest : leftovers.values()) {
            player.getWorld().dropItem(player.getLocation(), rest, item -> item.setOwner(player.getUniqueId()));
        }
        if (!leftovers.isEmpty()) {
            ctx.messages().send(player, "worth.returned-dropped");
        }
    }

    /** Stores items for later delivery. Never loses them: a storage failure logs the serialized items. */
    void persist(UUID player, List<ItemStack> items) {
        persist(player, items, false);
    }

    /** Blocking variant for module shutdown, where scheduled tasks may never run. */
    void persistBlocking(UUID player, List<ItemStack> items) {
        persist(player, items, true);
    }

    private void persist(UUID player, List<ItemStack> items, boolean blocking) {
        List<ItemStack> copy = nonEmpty(items);
        if (copy.isEmpty()) {
            return;
        }
        byte[] bytes = ItemStack.serializeItemsAsBytes(copy);
        var future = store.addReturn(player, bytes).whenComplete((v, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not store {} item stack(s) owed to {}. Base64 item data for manual restore: {}",
                        copy.size(), player, Base64.getEncoder().encodeToString(bytes), failure);
            }
        });
        if (blocking) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Already logged with the item data by whenComplete (or still running).
                ctx.logger().warn("Waiting for pending item return of {} failed", player, e);
            }
        }
    }

    /** Claims and delivers every pending return of an online player. */
    void deliver(Player player) {
        UUID uuid = player.getUniqueId();
        store.claimReturns(uuid).whenComplete((blobs, failure) -> {
            if (failure != null) {
                ctx.logger().warn("Could not load pending item returns of {}", uuid, failure);
                return;
            }
            if (blobs.isEmpty()) {
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            for (byte[] blob : blobs) {
                try {
                    for (ItemStack item : ItemStack.deserializeItemsFromBytes(blob)) {
                        if (item != null && !item.isEmpty()) items.add(item);
                    }
                } catch (RuntimeException e) {
                    ctx.logger().error("Corrupt pending item return of {}; Base64: {}", uuid,
                            Base64.getEncoder().encodeToString(blob), e);
                }
            }
            ctx.scheduler().entity(player, () -> {
                giveNow(player, items);
                ctx.messages().send(player, "worth.returned-pending");
            }, () -> persist(uuid, items));
        });
    }

    private static List<ItemStack> nonEmpty(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) out.add(item.clone());
        }
        return out;
    }
}
