package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Offline delivery for {@code /giveall ... offline}: one row per recipient in
 * {@code {prefix}itemtools_giveall}, claimed in one transaction on the next join. Rows are claimed (deleted)
 * before the items are handed over, so a crash can lose a queued gift but can never duplicate one. When the
 * player leaves between the claim and the delivery, the claimed blobs are queued again.
 */
final class GiveAllQueue implements Listener {
    private final ModuleContext ctx;
    private final Database db;
    private final String table;

    GiveAllQueue(ModuleContext ctx) {
        this.ctx = ctx;
        this.db = ctx.database();
        this.table = db.table("itemtools_giveall");
    }

    /** Queues the already-serialized items for every recipient in one transaction. Returns the row count. */
    CompletableFuture<Integer> enqueue(List<UUID> recipients, byte[] items) {
        if (recipients.isEmpty() || items == null || items.length == 0) {
            return CompletableFuture.completedFuture(0);
        }
        long created = System.currentTimeMillis();
        List<UUID> copy = List.copyOf(recipients);
        return db.transaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + table + " (uuid, items, created) VALUES (?, ?, ?)")) {
                for (UUID recipient : copy) {
                    ps.setString(1, recipient.toString());
                    ps.setBytes(2, items);
                    ps.setLong(3, created);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return copy.size();
        });
    }

    /** Removes and returns every queued gift of one player in one transaction. */
    CompletableFuture<List<byte[]>> claim(UUID player) {
        String select = "SELECT id, items FROM " + table + " WHERE uuid = ?";
        String delete = "DELETE FROM " + table + " WHERE id = ? AND uuid = ?";
        return db.transaction(connection -> {
            List<String> ids = new ArrayList<>();
            List<byte[]> blobs = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(select)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                        blobs.add(rs.getBytes(2));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(delete)) {
                for (String id : ids) {
                    ps.setString(1, id);
                    ps.setString(2, player.toString());
                    if (ps.executeUpdate() != 1) {
                        throw new java.sql.SQLException("Queued giveall row " + id + " vanished during claim");
                    }
                }
            }
            return List.copyOf(blobs);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        deliver(event.getPlayer());
    }

    private void deliver(Player player) {
        UUID uuid = player.getUniqueId();
        claim(uuid).whenComplete((blobs, error) -> {
            if (error != null) {
                ctx.logger().warn("Could not load the queued gifts of {}", uuid, error);
                return;
            }
            if (blobs.isEmpty()) {
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            for (byte[] blob : blobs) {
                try {
                    for (ItemStack item : ItemStack.deserializeItemsFromBytes(blob)) {
                        if (item != null && !item.isEmpty()) {
                            items.add(item);
                        }
                    }
                } catch (RuntimeException e) {
                    ctx.logger().error("Corrupt queued giveall item for {}; Base64: {}", uuid,
                            Base64.getEncoder().encodeToString(blob), e);
                }
            }
            if (items.isEmpty()) {
                return;
            }
            int total = items.stream().mapToInt(ItemStack::getAmount).sum();
            ctx.scheduler().entity(player, () -> {
                if (!player.isOnline()) {
                    requeue(uuid, blobs);
                    return;
                }
                ItemValues.giveOrDrop(player, items);
                ctx.messages().send(player, "itemtools.giveall-received", Msg.number("amount", total));
            }, () -> requeue(uuid, blobs));
        });
    }

    /** Puts claimed but undelivered items back, one row per original blob. */
    private void requeue(UUID player, List<byte[]> blobs) {
        for (byte[] blob : blobs) {
            enqueue(List.of(player), blob).exceptionally(error -> {
                ctx.logger().error("Could not restore a claimed giveall gift for {}; Base64: {}", player,
                        Base64.getEncoder().encodeToString(blob), error);
                return null;
            });
        }
    }
}
