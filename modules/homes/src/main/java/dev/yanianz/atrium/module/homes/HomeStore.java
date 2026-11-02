package dev.yanianz.atrium.module.homes;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Homes table, exposed as the per-player {@link Homes} component. */
final class HomeStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "homes_homes",
            "CREATE TABLE {prefix}homes_homes (uuid CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "name_lower VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, "
                    + "z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid, name_lower))"));

    private final Database db;
    final UserComponentType<Homes> component;

    HomeStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("homes:homes", this::load, this::save, () -> Homes.EMPTY);
    }

    private CompletableFuture<Homes> load(UUID uuid) {
        return db.query(c -> {
            List<Home> homes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, world, x, y, z, yaw, pitch, created FROM "
                    + db.table("homes_homes") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        homes.add(new Home(rs.getString(1), new StoredLocation(rs.getString(2), rs.getDouble(3),
                                rs.getDouble(4), rs.getDouble(5), rs.getFloat(6), rs.getFloat(7)), rs.getLong(8)));
                    }
                }
            }
            return homes.isEmpty() ? null : Homes.of(homes);
        });
    }

    /** Replaces the player's rows with the given snapshot in one transaction. */
    private CompletableFuture<Void> save(UUID uuid, Homes homes) {
        String table = db.table("homes_homes");
        return db.transaction(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            if (homes.count() > 0) {
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table
                        + " (uuid, name, name_lower, world, x, y, z, yaw, pitch, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Home home : homes.list()) {
                        StoredLocation l = home.location();
                        Database.bind(insert, uuid.toString(), home.name(), Homes.key(home.name()), l.world(),
                                l.x(), l.y(), l.z(), l.yaw(), l.pitch(), home.createdAt());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            return null;
        });
    }
}
