package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Player vaults in {@code {prefix}inventories_vaults}: one row per owner and vault number, holding the serialized
 * contents and the size they were saved with.
 *
 * <p>The row is replaced in one statement, so a crash can never leave half a vault. {@code size} is stored so a
 * vault saved with six rows is not silently loaded into a smaller menu if the owner's permission drops: the
 * loader trims or pads to the current size and the items that no longer fit are handed back, never deleted.
 */
final class VaultStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(4, "player vaults", dialect -> {
        String blob = dialect == Dialect.SQLITE ? "BLOB" : "MEDIUMBLOB";
        return List.of("CREATE TABLE {prefix}inventories_vaults (uuid CHAR(36) NOT NULL, vault INT NOT NULL"
                + ", rows INT NOT NULL, size INT NOT NULL, updated BIGINT NOT NULL, items " + blob + " NOT NULL"
                + ", PRIMARY KEY (uuid, vault))");
    }));

    /** One stored vault: the saved size and the raw item bytes. */
    record Stored(int rows, int size, byte[] items, long updated) {}

    private final Database db;

    VaultStore(Database db) {
        this.db = db;
    }

    CompletableFuture<Optional<Stored>> load(UUID owner, int vault) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT rows, size, items, updated FROM "
                    + db.table("inventories_vaults") + " WHERE uuid = ? AND vault = ?")) {
                ps.setString(1, owner.toString());
                ps.setInt(2, vault);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Stored(rs.getInt(1), rs.getInt(2), rs.getBytes(3), rs.getLong(4)));
                }
            }
        });
    }

    /** Writes the whole vault in one statement (insert or replace). */
    CompletableFuture<Void> save(UUID owner, int vault, int rows, int size, byte[] items) {
        String sql = db.dialect().upsert(db.table("inventories_vaults"),
                "uuid, vault, rows, size, updated, items", "?, ?, ?, ?, ?, ?", "uuid, vault",
                "rows, size, updated, items");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, owner.toString(), vault, rows, size, System.currentTimeMillis(), items);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Numbers of the vaults that exist for this owner, oldest first. */
    CompletableFuture<List<Integer>> list(UUID owner) {
        return db.query(c -> {
            List<Integer> vaults = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT vault FROM " + db.table("inventories_vaults")
                    + " WHERE uuid = ? ORDER BY vault")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        vaults.add(rs.getInt(1));
                    }
                }
            }
            return List.copyOf(vaults);
        });
    }

    CompletableFuture<Boolean> delete(UUID owner, int vault) {
        return db.update("DELETE FROM {prefix}inventories_vaults WHERE uuid = ? AND vault = ?",
                owner.toString(), vault).thenApply(deleted -> deleted > 0);
    }
}
