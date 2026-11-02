package dev.yanianz.atrium.module.portals;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/** Portal table {@code {prefix}portals_portals}. All calls run on the storage executor. */
final class PortalStorage {
    static final List<Migration> MIGRATIONS = List.of(
            Migration.of(1, "portals_portals",
                    "CREATE TABLE {prefix}portals_portals (name_lower VARCHAR(32) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                            + "world VARCHAR(128) NOT NULL, min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, "
                            + "max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL, target VARCHAR(512) NOT NULL, "
                            + "action_line VARCHAR(1024), permission VARCHAR(128), creator CHAR(36), created BIGINT NOT NULL)"),
            Migration.of(2, "portals cost per use",
                    "ALTER TABLE {prefix}portals_portals ADD COLUMN cost DOUBLE"));

    private static final String COLUMNS = "name_lower, name, world, min_x, min_y, min_z, max_x, max_y, max_z, target, "
            + "action_line, permission, creator, created, cost";

    private final Database db;
    private final Logger logger;

    PortalStorage(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    CompletableFuture<List<Portal>> loadAll() {
        return db.query(c -> {
            List<Portal> portals = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM " + db.table("portals_portals"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(2);
                    try {
                        Cuboid area = Cuboid.of(rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                                rs.getInt(7), rs.getInt(8), rs.getInt(9));
                        String creator = rs.getString(13);
                        double cost = rs.getDouble(15);
                        boolean hasCost = !rs.wasNull();
                        portals.add(new Portal(name, area, PortalTarget.decode(rs.getString(10)), rs.getString(11),
                                rs.getString(12), creator == null ? null : UUID.fromString(creator), rs.getLong(14),
                                hasCost ? cost : null));
                    } catch (IllegalArgumentException e) {
                        logger.warn("Skipping broken portal row '{}': {}", name, e.getMessage());
                    }
                }
            }
            return portals;
        });
    }

    /** Inserts a new portal; fails (primary key) when the name is taken. */
    CompletableFuture<Void> insert(Portal portal) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("portals_portals") + " (" + COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(ps, portal);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Writes only the settings that differ between {@code before} and {@code after} (target, action line,
     * permission), so concurrent edits of different settings do not overwrite each other. Completes with
     * false when the portal row is gone.
     */
    CompletableFuture<Boolean> updateSettings(Portal after, Portal before) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!after.target().equals(before.target())) {
            sets.add("target = ?");
            params.add(after.target().encode());
        }
        if (!java.util.Objects.equals(after.action(), before.action())) {
            sets.add("action_line = ?");
            params.add(after.action());
        }
        if (!java.util.Objects.equals(after.permission(), before.permission())) {
            sets.add("permission = ?");
            params.add(after.permission());
        }
        if (!java.util.Objects.equals(after.cost(), before.cost())) {
            sets.add("cost = ?");
            params.add(after.cost());
        }
        if (sets.isEmpty()) {
            sets.add("name_lower = name_lower");
        }
        params.add(after.key());
        return db.update("UPDATE {prefix}portals_portals SET " + String.join(", ", sets) + " WHERE name_lower = ?",
                params.toArray()).thenApply(n -> n > 0);
    }

    CompletableFuture<Boolean> delete(String key) {
        return db.update("DELETE FROM {prefix}portals_portals WHERE name_lower = ?", key).thenApply(n -> n > 0);
    }

    private static void bind(PreparedStatement ps, Portal portal) throws java.sql.SQLException {
        Cuboid a = portal.area();
        Database.bind(ps, portal.key(), portal.name(), a.world(), a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ(),
                portal.target().encode(), portal.action(), portal.permission(),
                portal.creator() == null ? null : portal.creator().toString(), portal.createdAt(), portal.cost());
    }
}
