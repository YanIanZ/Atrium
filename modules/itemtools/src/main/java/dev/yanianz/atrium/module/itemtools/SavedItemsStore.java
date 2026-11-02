package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The saved item library in {@code {prefix}itemtools_saveditems}. Items are stored as the exact bytes of
 * {@link org.bukkit.inventory.ItemStack#serializeAsBytes()}, so every component (name, lore, enchantments,
 * container contents) survives a save and a get. Names are normalised by {@link SavedItemName}.
 */
final class SavedItemsStore {
    static final int MAX_ENTRIES = 500;

    /** One stored item; {@code item} is the serialized stack and is never mutated by this class. */
    record Entry(String name, byte[] item, @Nullable String savedBy, long created) {
    }

    private final Database db;
    private final String table;

    SavedItemsStore(Database db) {
        this.db = db;
        this.table = db.table("itemtools_saveditems");
    }

    /** Inserts or replaces one entry. */
    CompletableFuture<Void> save(String name, byte[] item, @Nullable UUID savedBy) {
        String sql = db.dialect().upsert(table, "name, item, saved_by, created", "?, ?, ?, ?",
                "name", "item, saved_by, created");
        long created = System.currentTimeMillis();
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setBytes(2, item);
                ps.setString(3, savedBy == null ? null : savedBy.toString());
                ps.setLong(4, created);
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Optional<byte[]>> load(String name) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT item FROM " + table + " WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
                }
            }
        });
    }

    /** Every entry in name order, at most {@link #MAX_ENTRIES}. */
    CompletableFuture<List<Entry>> list() {
        return db.query(connection -> {
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name, item, saved_by, created FROM " + table + " ORDER BY name LIMIT ?")) {
                ps.setInt(1, MAX_ENTRIES);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new Entry(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getLong(4)));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }

    /** Deletes one entry; the future completes with whether a row actually existed. */
    CompletableFuture<Boolean> delete(String name) {
        return db.update("DELETE FROM " + table + " WHERE name = ?", name).thenApply(removed -> removed > 0);
    }
}
