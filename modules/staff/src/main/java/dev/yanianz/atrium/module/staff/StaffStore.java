package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Storage of staff snapshots and the two per-player switches (command spy, spectator session). */
final class StaffStore {
    static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "staff snapshots", dialect -> List.of(
                    "CREATE TABLE {prefix}staff_snapshots (uuid CHAR(36) NOT NULL PRIMARY KEY, format INT NOT NULL,"
                            + " data " + (dialect == Dialect.MYSQL ? "LONGBLOB" : "BLOB") + " NOT NULL,"
                            + " created BIGINT NOT NULL)")),
            Migration.of(2, "staff preferences",
                    "CREATE TABLE {prefix}staff_preferences (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                            + " command_spy BOOLEAN NOT NULL DEFAULT FALSE)"),
            Migration.of(3, "staff spectate",
                    "CREATE TABLE {prefix}staff_spectate (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                            + " active BOOLEAN NOT NULL, world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL,"
                            + " y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL,"
                            + " game_mode VARCHAR(16) NOT NULL, target VARCHAR(16) NOT NULL)"),
            // Two-phase restore: ACTIVE -> RESTORING (confirmed before the inventory is touched) -> deleted.
            // Rows written before this column existed were live sessions, hence the ACTIVE default.
            Migration.of(4, "staff snapshot state",
                    "ALTER TABLE {prefix}staff_snapshots ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'"));

    private final Database db;
    private final Logger logger;

    final UserComponentType<StaffPreferences> preferences;
    final UserComponentType<SpectateState> spectate;

    StaffStore(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
        this.preferences = new UserComponentType<>("staff:preferences", this::loadPreferences,
                this::savePreferences, () -> StaffPreferences.DEFAULT);
        this.spectate = new UserComponentType<>("staff:spectate", this::loadSpectate,
                this::saveSpectate, () -> SpectateState.EMPTY);
    }

    // ---- staff snapshots ----

    /** A stored row. {@code snapshot} is {@code null} when the row is corrupt (kept for review, never applied). */
    record Stored(SnapshotLifecycle.State state, @Nullable StaffSnapshot snapshot, long created) {
        boolean corrupt() {
            return snapshot == null;
        }
    }

    /** The stored row of a player, or {@code null} when there is none. */
    CompletableFuture<@Nullable Stored> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT data, state, created FROM " + db.table("staff_snapshots") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    SnapshotLifecycle.State state = SnapshotLifecycle.State.parse(rs.getString(2));
                    long created = rs.getLong(3);
                    try {
                        return new Stored(state, StaffSnapshotCodec.decode(rs.getBytes(1)), created);
                    } catch (RuntimeException e) {
                        logger.error("Stored staff snapshot of {} is corrupt; it is kept for /staffmode recover",
                                uuid, e);
                        return new Stored(state, null, created);
                    }
                }
            }
        });
    }

    /** Players with a row in the given state (for {@code /staffmode recover}). */
    CompletableFuture<List<UUID>> withState(SnapshotLifecycle.State state) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM " + db.table("staff_snapshots") + " WHERE state = ? ORDER BY created")) {
                ps.setString(1, state.name());
                List<UUID> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(UUID.fromString(rs.getString(1)));
                    }
                }
                return result;
            }
        });
    }

    /**
     * Stores a new ACTIVE snapshot, in one transaction, only when the player has no row yet. Completes with
     * {@code false} (and changes nothing) when a row exists, so a held RESTORING row is never overwritten.
     */
    CompletableFuture<Boolean> begin(UUID uuid, StaffSnapshot snapshot) {
        byte[] data = StaffSnapshotCodec.encode(snapshot);
        return db.transaction(c -> {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM " + db.table("staff_snapshots") + " WHERE uuid = ?")) {
                check.setString(1, uuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("staff_snapshots")
                    + " (uuid, format, data, created, state) VALUES (?, ?, ?, ?, ?)")) {
                Database.bind(ps, uuid.toString(), snapshot.format(), data, System.currentTimeMillis(),
                        SnapshotLifecycle.State.ACTIVE.name());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Compare-and-set of the state. Completes with {@code true} only when exactly that row changed. */
    CompletableFuture<Boolean> transition(UUID uuid, SnapshotLifecycle.State from, SnapshotLifecycle.State to) {
        return db.update("UPDATE {prefix}staff_snapshots SET state = ? WHERE uuid = ? AND state = ?",
                to.name(), uuid.toString(), from.name()).thenApply(rows -> rows == 1);
    }

    /**
     * Marks a RESTORING row as restored by deleting it. Only a RESTORING row is deleted, so a row that was
     * reverted to ACTIVE in the meantime survives.
     */
    CompletableFuture<Boolean> finish(UUID uuid) {
        return db.update("DELETE FROM {prefix}staff_snapshots WHERE uuid = ? AND state = ?",
                uuid.toString(), SnapshotLifecycle.State.RESTORING.name()).thenApply(rows -> rows == 1);
    }

    // ---- preferences ----

    private CompletableFuture<StaffPreferences> loadPreferences(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT command_spy FROM " + db.table("staff_preferences") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new StaffPreferences(rs.getBoolean(1)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> savePreferences(UUID uuid, StaffPreferences value) {
        String sql = db.dialect().upsert(db.table("staff_preferences"), "uuid, command_spy", "?, ?", "uuid",
                "command_spy");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), value.commandSpy());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- spectator session ----

    private CompletableFuture<SpectateState> loadSpectate(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT active, world, x, y, z, yaw, pitch, game_mode,"
                    + " target FROM " + db.table("staff_spectate") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new SpectateState(rs.getBoolean(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getFloat(6), rs.getFloat(7), rs.getString(8), rs.getString(9));
                }
            }
        });
    }

    private CompletableFuture<Void> saveSpectate(UUID uuid, SpectateState value) {
        String sql = db.dialect().upsert(db.table("staff_spectate"),
                "uuid, active, world, x, y, z, yaw, pitch, game_mode, target",
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?", "uuid",
                "active, world, x, y, z, yaw, pitch, game_mode, target");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), value.active(), value.world(), value.x(), value.y(), value.z(),
                        value.yaw(), value.pitch(), value.gameMode(), value.target());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
