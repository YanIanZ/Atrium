package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Tables of the teleport module: per-player state (a user component), spawn points and cooldowns. */
final class TeleportStore {
    static final List<Migration> MIGRATIONS = List.of(
            Migration.of(1, "teleport_players and teleport_spawns",
                    "CREATE TABLE {prefix}teleport_players (uuid CHAR(36) NOT NULL PRIMARY KEY, back_location VARCHAR(255), "
                            + "back_death BOOLEAN NOT NULL, back_time BIGINT NOT NULL, accepts_requests BOOLEAN NOT NULL)",
                    "CREATE TABLE {prefix}teleport_spawns (id VARCHAR(96) NOT NULL PRIMARY KEY, location VARCHAR(255) NOT NULL)"),
            Migration.of(2, "back history and persisted cooldowns",
                    "ALTER TABLE {prefix}teleport_players ADD COLUMN back_history TEXT",
                    "CREATE TABLE {prefix}teleport_cooldowns (uuid CHAR(36) NOT NULL, cause VARCHAR(32) NOT NULL, "
                            + "expires_at BIGINT NOT NULL, PRIMARY KEY (uuid, cause))"));

    /** Spawn ids. World spawns are {@code world:<name>}. */
    static final String DEFAULT_SPAWN = "default";
    static final String FIRST_JOIN_SPAWN = "first-join";

    static String worldSpawnId(String world) {
        return "world:" + world;
    }

    private final Database db;
    final UserComponentType<TeleportState> component;

    TeleportStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("teleport:state", this::load, this::save, () -> TeleportState.DEFAULT);
    }

    CompletableFuture<TeleportState> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT back_location, back_death, back_time, accepts_requests, "
                    + "back_history FROM " + db.table("teleport_players") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    BackHistory history = BackHistory.decode(rs.getString(5));
                    if (history.isEmpty()) {
                        // Rows written before migration 2 only have the single back slot.
                        Optional<StoredLocation> legacy = StoredLocation.decode(rs.getString(1));
                        if (legacy.isPresent()) {
                            history = new BackHistory(List.of(new BackHistory.Entry(legacy.get(), rs.getBoolean(2), rs.getLong(3))));
                        }
                    }
                    return new TeleportState(history, rs.getBoolean(4));
                }
            }
        });
    }

    CompletableFuture<Void> save(UUID uuid, TeleportState state) {
        String sql = db.dialect().upsert(db.table("teleport_players"),
                "uuid, back_location, back_death, back_time, accepts_requests, back_history", "?, ?, ?, ?, ?, ?", "uuid",
                "back_location, back_death, back_time, accepts_requests, back_history");
        Optional<BackHistory.Entry> latest = state.back().latest();
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                // back_location/back_death/back_time keep mirroring the newest entry for older readers.
                Database.bind(ps, uuid.toString(), latest.map(e -> e.location().encode()).orElse(null),
                        latest.map(BackHistory.Entry::death).orElse(false), latest.map(BackHistory.Entry::recordedAt).orElse(0L),
                        state.acceptsRequests(), state.back().isEmpty() ? null : state.back().encode());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- cooldowns ----

    /** A persisted cooldown. */
    record CooldownRow(UUID player, TeleportCause cause, long expiresAt) {}

    /** Every cooldown that has not expired at {@code now} (epoch millis). Rows with unknown causes are skipped. */
    CompletableFuture<List<CooldownRow>> loadCooldowns(long now) {
        return db.query(c -> {
            List<CooldownRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, cause, expires_at FROM "
                    + db.table("teleport_cooldowns") + " WHERE expires_at > ?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            rows.add(new CooldownRow(UUID.fromString(rs.getString(1)),
                                    TeleportCause.valueOf(rs.getString(2)), rs.getLong(3)));
                        } catch (IllegalArgumentException e) {
                            // unknown cause or malformed uuid: ignore the row
                        }
                    }
                }
            }
            return rows;
        });
    }

    CompletableFuture<Void> saveCooldown(UUID player, TeleportCause cause, long expiresAt) {
        String sql = db.dialect().upsert(db.table("teleport_cooldowns"), "uuid, cause, expires_at", "?, ?, ?",
                "uuid, cause", "expires_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, player.toString(), cause.name(), expiresAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> deleteCooldown(UUID player, TeleportCause cause) {
        return db.update("DELETE FROM {prefix}teleport_cooldowns WHERE uuid = ? AND cause = ?", player.toString(), cause.name())
                .thenApply(n -> null);
    }

    /** Removes expired rows. */
    CompletableFuture<Integer> purgeCooldowns(long now) {
        return db.update("DELETE FROM {prefix}teleport_cooldowns WHERE expires_at <= ?", now);
    }

    CompletableFuture<Map<String, StoredLocation>> loadSpawns() {
        return db.query(c -> {
            Map<String, StoredLocation> spawns = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, location FROM " + db.table("teleport_spawns"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    StoredLocation.decode(rs.getString(2)).ifPresent(l -> spawns.put(id, l));
                }
            }
            return spawns;
        });
    }

    CompletableFuture<Void> saveSpawn(String id, StoredLocation location) {
        String sql = db.dialect().upsert(db.table("teleport_spawns"), "id, location", "?, ?", "id", "location");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, id, location.encode());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Boolean> deleteSpawn(String id) {
        return db.update("DELETE FROM {prefix}teleport_spawns WHERE id = ?", id).thenApply(n -> n > 0);
    }
}
