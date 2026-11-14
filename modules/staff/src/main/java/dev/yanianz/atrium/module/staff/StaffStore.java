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
                    "ALTER TABLE {prefix}staff_snapshots ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'"),
            // Versions 5-7 are one DDL statement each on purpose. MySQL/MariaDB implicitly commit around every
            // DDL statement, so Database.migrate's transaction cannot roll earlier statements back when a later
            // one fails; the version bump then stays behind. One statement per version confines that window to
            // exactly the statement that failed and lets earlier steps keep their committed version.
            // No IF NOT EXISTS: MySQL 8 rejects "ADD COLUMN IF NOT EXISTS" (MariaDB accepts it), and Dialect
            // maps MYSQL and MARIADB to the same value, so it must not be assumed. A crash between the implicit
            // DDL commit and the version bump therefore still needs a manual column check before retrying.
            Migration.of(5, "staff snapshot identity",
                    "ALTER TABLE {prefix}staff_snapshots ADD COLUMN snapshot_id CHAR(36) NOT NULL DEFAULT ''"),
            Migration.of(6, "staff snapshot origin",
                    "ALTER TABLE {prefix}staff_snapshots ADD COLUMN origin_server VARCHAR(128) NOT NULL DEFAULT ''"),
            new Migration(7, "staff snapshot recovery archive", dialect -> List.of(
                    "CREATE TABLE {prefix}staff_snapshot_history (archive_id CHAR(36) NOT NULL PRIMARY KEY,"
                            + " uuid CHAR(36) NOT NULL, snapshot_id CHAR(36) NOT NULL, origin_server VARCHAR(128) NOT NULL,"
                            + " format INT NOT NULL, data " + (dialect == Dialect.MYSQL ? "LONGBLOB" : "BLOB")
                            + " NOT NULL, created BIGINT NOT NULL, archived BIGINT NOT NULL)")));

    private final Database db;
    private final Logger logger;
    private final java.util.function.Supplier<String> origin;

    final UserComponentType<StaffPreferences> preferences;
    final UserComponentType<SpectateState> spectate;

    StaffStore(Database db, Logger logger) {
        this(db, logger, () -> "local");
    }

    StaffStore(Database db, Logger logger, java.util.function.Supplier<String> origin) {
        this.db = db;
        this.logger = logger;
        this.origin = origin;
        this.preferences = new UserComponentType<>("staff:preferences", this::loadPreferences,
                this::savePreferences, () -> StaffPreferences.DEFAULT);
        this.spectate = new UserComponentType<>("staff:spectate", this::loadSpectate,
                this::saveSpectate, () -> SpectateState.EMPTY);
    }

    // ---- staff snapshots ----

    /** A stored row. {@code snapshot} is {@code null} when the row is corrupt (kept for review, never applied). */
    record Stored(SnapshotLifecycle.State state, @Nullable StaffSnapshot snapshot, long created,
            String token, String origin) {
        boolean corrupt() {
            return snapshot == null;
        }
    }

    /** The stored row of a player, or {@code null} when there is none. */
    CompletableFuture<@Nullable Stored> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT data, state, created, snapshot_id, origin_server FROM "
                            + db.table("staff_snapshots") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    SnapshotLifecycle.State state = SnapshotLifecycle.State.parse(rs.getString(2));
                    long created = rs.getLong(3);
                    try {
                        return new Stored(state, StaffSnapshotCodec.decode(rs.getBytes(1)), created,
                                rs.getString(4), rs.getString(5));
                    } catch (RuntimeException e) {
                        logger.error("Stored staff snapshot of {} is corrupt; it is kept for /staffmode recover",
                                uuid, e);
                        return new Stored(state, null, created, rs.getString(4), rs.getString(5));
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
                    + " (uuid, format, data, created, state, snapshot_id, origin_server)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                Database.bind(ps, uuid.toString(), snapshot.format(), data, System.currentTimeMillis(),
                        SnapshotLifecycle.State.ACTIVE.name(), UUID.randomUUID().toString(), origin.get());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Compare-and-set of the state. Completes with {@code true} only when exactly that row changed. */
    CompletableFuture<Boolean> transition(UUID uuid, Stored expected, SnapshotLifecycle.State from, SnapshotLifecycle.State to) {
        return db.update("UPDATE {prefix}staff_snapshots SET state = ? WHERE uuid = ? AND state = ?"
                        + " AND snapshot_id = ? AND created = ?",
                to.name(), uuid.toString(), from.name(), expected.token(), expected.created()).thenApply(rows -> rows == 1);
    }

    /**
     * Marks a RESTORING row as restored by deleting it. Only a RESTORING row is deleted, so a row that was
     * reverted to ACTIVE in the meantime survives.
     *
     * <p>The archive copy and the delete run in one transaction and share one predicate: the row is identified
     * by {@code uuid} (primary key) plus the token/created pair of the loaded row, so a row written after that
     * load can never be archived or deleted here. Rows live in the snapshot table only; the archive copy is
     * copied from the live row in the same statement, so no caller-supplied payload can diverge from it. A
     * concurrent change between the copy and the delete makes the delete affect zero rows, which throws and
     * rolls back both. MySQL/MariaDB implicit commit does not apply: both statements are plain DML.
     */
    CompletableFuture<Boolean> finish(UUID uuid, Stored expected) {
        return db.transaction(c -> {
            // Archive the exact row before deleting it. A later inventory overwrite must not destroy
            // the last recovery copy. Archives are never replayed automatically.
            String predicate = " WHERE uuid = ? AND state = ? AND snapshot_id = ? AND created = ?";
            try (PreparedStatement archive = c.prepareStatement("INSERT INTO " + db.table("staff_snapshot_history")
                    + " (archive_id, uuid, snapshot_id, origin_server, format, data, created, archived)"
                    + " SELECT ?, uuid, snapshot_id, origin_server, format, data, created, ? FROM "
                    + db.table("staff_snapshots") + predicate)) {
                Database.bind(archive, UUID.randomUUID().toString(), System.currentTimeMillis(), uuid.toString(),
                        SnapshotLifecycle.State.RESTORING.name(), expected.token(), expected.created());
                if (archive.executeUpdate() != 1) return false;
            }
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + db.table("staff_snapshots") + predicate)) {
                Database.bind(delete, uuid.toString(), SnapshotLifecycle.State.RESTORING.name(),
                        expected.token(), expected.created());
                if (delete.executeUpdate() != 1) throw new java.sql.SQLException("Staff snapshot changed during finish");
                return true;
            }
        });
    }

    /**
     * Binds a row written before migration 5 (blank {@code snapshot_id}/{@code origin_server}) to an explicit,
     * operator-chosen origin. Single conditional update, so it is atomic on both dialects.
     *
     * <p>Only the identity of the row loaded by the caller is used: {@code uuid} plus its token/created pair,
     * and only while its origin is still blank. Nothing else is written — the payload, format, created stamp
     * and state are untouched. When the legacy row has no token yet, a fresh token is assigned so later
     * compare-and-set operations can tell row versions apart.
     *
     * <p>There is no automatic adoption or backfill: an unknown origin cannot be inferred on a shared network,
     * so the parent module decides when (and for which row) an operator runs this. Completes with {@code false}
     * when the row was already adopted, was replaced by a newer snapshot, or does not exist.
     *
     * @throws IllegalArgumentException when {@code origin} is blank
     */
    CompletableFuture<Boolean> adoptLegacy(UUID uuid, Stored legacy, String origin) {
        String boundOrigin = origin == null ? "" : origin.strip();
        if (boundOrigin.isEmpty()) {
            throw new IllegalArgumentException("adoptLegacy needs an explicit origin, not a blank one");
        }
        String token = legacy.token().isBlank() ? UUID.randomUUID().toString() : legacy.token();
        return db.update("UPDATE {prefix}staff_snapshots SET snapshot_id = ?, origin_server = ?"
                        + " WHERE uuid = ? AND snapshot_id = ? AND created = ? AND origin_server = ''",
                token, boundOrigin, uuid.toString(), legacy.token(), legacy.created())
                .thenApply(rows -> rows == 1);
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
