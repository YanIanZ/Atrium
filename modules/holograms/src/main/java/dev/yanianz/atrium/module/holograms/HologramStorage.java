package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Tables {@code {prefix}holograms_holograms} (one row per hologram, settings and actions as text) and
 * {@code {prefix}holograms_lines} (one row per line). A save rewrites one hologram in a single transaction.
 */
final class HologramStorage {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "holograms tables", dialect -> {
        String text = dialect == Dialect.MYSQL ? "MEDIUMTEXT" : "TEXT";
        return List.of(
                "CREATE TABLE {prefix}holograms_holograms (name_lower VARCHAR(32) NOT NULL PRIMARY KEY, "
                        + "name VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, "
                        + "z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, pages INT NOT NULL, "
                        + "settings " + text + " NOT NULL, actions " + text + " NOT NULL, created BIGINT NOT NULL)",
                "CREATE TABLE {prefix}holograms_lines (hologram VARCHAR(32) NOT NULL, page INT NOT NULL, "
                        + "line INT NOT NULL, content " + text + " NOT NULL, off_x FLOAT NOT NULL, off_y FLOAT NOT NULL, "
                        + "off_z FLOAT NOT NULL, PRIMARY KEY (hologram, page, line))");
    }));

    private final Database db;
    private final Logger logger;

    HologramStorage(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /** Loads every hologram. Settings missing from a row (new options) take {@code defaults}. */
    CompletableFuture<List<Hologram>> loadAll(Supplier<HologramSettings> defaults) {
        return db.query(c -> {
            Map<String, List<List<HoloLine>>> lines = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT hologram, page, line, content, off_x, off_y, off_z FROM "
                    + db.table("holograms_lines") + " ORDER BY hologram, page, line");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<List<HoloLine>> pages = lines.computeIfAbsent(rs.getString(1), k -> new ArrayList<>());
                    int page = rs.getInt(2);
                    while (pages.size() <= page) {
                        pages.add(new ArrayList<>());
                    }
                    pages.get(page).add(new HoloLine(rs.getString(4), rs.getFloat(5), rs.getFloat(6), rs.getFloat(7)));
                }
            }
            List<Hologram> holograms = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name_lower, name, world, x, y, z, yaw, pitch, pages, "
                    + "settings, actions, created FROM " + db.table("holograms_holograms"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String name = rs.getString(2);
                    try {
                        holograms.add(read(rs, name, lines.getOrDefault(key, List.of()), defaults.get()));
                    } catch (RuntimeException e) {
                        logger.error("Skipping hologram {}: stored data is invalid", name, e);
                    }
                }
            }
            return holograms;
        });
    }

    private Hologram read(ResultSet rs, String name, List<List<HoloLine>> stored, HologramSettings defaults)
            throws SQLException {
        StoredLocation location = new StoredLocation(rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                rs.getFloat(7), rs.getFloat(8));
        int pageCount = Math.max(1, rs.getInt(9));
        List<List<HoloLine>> pages = new ArrayList<>(stored);
        while (pages.size() < pageCount) {
            pages.add(List.of());
        }
        HologramSettings settings = HologramSettings.decode(rs.getString(10), defaults,
                (key, problem) -> logger.warn("Hologram {}: setting {} ignored ({})", name, key, problem));
        List<ClickAction> actions = new ArrayList<>();
        String actionText = rs.getString(11);
        if (actionText != null) {
            for (String line : actionText.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    actions.add(ClickAction.parse(line));
                } catch (IllegalArgumentException e) {
                    logger.warn("Hologram {}: click action '{}' ignored ({})", name, line, e.getMessage());
                }
            }
        }
        return new Hologram(name, location, settings, pages, actions, rs.getLong(12));
    }

    CompletableFuture<Void> save(Hologram hologram) {
        String upsert = db.dialect().upsert(db.table("holograms_holograms"),
                "name_lower, name, world, x, y, z, yaw, pitch, pages, settings, actions, created",
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?", "name_lower",
                "name, world, x, y, z, yaw, pitch, pages, settings, actions, created");
        return db.transaction(c -> {
            StoredLocation l = hologram.location();
            StringBuilder actions = new StringBuilder();
            for (ClickAction action : hologram.actions()) {
                actions.append(action.encode()).append('\n');
            }
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                Database.bind(ps, hologram.key(), hologram.name(), l.world(), l.x(), l.y(), l.z(), l.yaw(), l.pitch(),
                        hologram.pages().size(), hologram.settings().encode(), actions.toString(), hologram.createdAt());
                ps.executeUpdate();
            }
            deleteLines(c, hologram.key());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("holograms_lines")
                    + " (hologram, page, line, content, off_x, off_y, off_z) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (int page = 0; page < hologram.pages().size(); page++) {
                    List<HoloLine> lines = hologram.pages().get(page);
                    for (int i = 0; i < lines.size(); i++) {
                        HoloLine line = lines.get(i);
                        Database.bind(ps, hologram.key(), page, i, line.raw(), line.offsetX(), line.offsetY(), line.offsetZ());
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    CompletableFuture<Void> delete(String key) {
        return db.transaction(c -> {
            deleteLines(c, key);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("holograms_holograms")
                    + " WHERE name_lower = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void deleteLines(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("holograms_lines") + " WHERE hologram = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }
}
