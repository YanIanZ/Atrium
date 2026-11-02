package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Storage of the punishment history ({@code {prefix}moderation_punishments}, one table for every kind) and of
 * the last known address / exempt flag per player ({@code {prefix}moderation_players}).
 */
final class PunishmentStore {
    static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "punishments, players, jails", dialect -> List.of(
                    "CREATE TABLE {prefix}moderation_punishments ("
                            + (dialect == Dialect.SQLITE
                                    ? "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                    : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, ")
                            + "type VARCHAR(16) NOT NULL, target CHAR(36), target_name VARCHAR(16), ip VARCHAR(64), "
                            + "actor CHAR(36), actor_name VARCHAR(64) NOT NULL, reason VARCHAR(512) NOT NULL, "
                            + "created BIGINT NOT NULL, expires BIGINT, revoked_by CHAR(36), revoked_by_name VARCHAR(64), "
                            + "revoked_at BIGINT, context VARCHAR(64))",
                    "CREATE INDEX {prefix}moderation_punishments_target ON {prefix}moderation_punishments (target, revoked_at)",
                    "CREATE INDEX {prefix}moderation_punishments_type ON {prefix}moderation_punishments (type, revoked_at)",
                    "CREATE TABLE {prefix}moderation_players (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                            + "last_ip VARCHAR(64), exempt BOOLEAN NOT NULL, updated BIGINT NOT NULL)",
                    "CREATE TABLE {prefix}moderation_jails (name VARCHAR(32) NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                            + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw DOUBLE NOT NULL, "
                            + "pitch DOUBLE NOT NULL, radius DOUBLE NOT NULL)")),
            Migration.of(2, "jail release locations",
                    "CREATE TABLE {prefix}moderation_release_locations (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                            + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                            + "yaw DOUBLE NOT NULL, pitch DOUBLE NOT NULL)"),
            Migration.of(3, "per-account login address locks",
                    "CREATE TABLE {prefix}moderation_lockip (uuid CHAR(36) NOT NULL, address VARCHAR(64) NOT NULL, "
                            + "actor_name VARCHAR(64) NOT NULL, created BIGINT NOT NULL, "
                            + "PRIMARY KEY (uuid, address))"),
            new Migration(5, "jail cells, sentence pauses, chat mute state and staff notes", dialect -> List.of(
                    "CREATE TABLE {prefix}moderation_jail_cells (jail VARCHAR(32) NOT NULL, cell INT NOT NULL, "
                            + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                            + "yaw DOUBLE NOT NULL, pitch DOUBLE NOT NULL, PRIMARY KEY (jail, cell))",
                    "CREATE TABLE {prefix}moderation_jail_pauses (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                            + "paused_at BIGINT NOT NULL)",
                    "CREATE TABLE {prefix}moderation_state (state_key VARCHAR(32) NOT NULL PRIMARY KEY, "
                            + "expires_at BIGINT, reason VARCHAR(512), actor_name VARCHAR(64) NOT NULL)",
                    "CREATE TABLE {prefix}moderation_notes ("
                            + (dialect == Dialect.SQLITE
                                    ? "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
                                    : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, ")
                            + "target CHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, actor CHAR(36), "
                            + "actor_name VARCHAR(64) NOT NULL, body VARCHAR(512) NOT NULL, created BIGINT NOT NULL)",
                    "CREATE INDEX {prefix}moderation_notes_target ON {prefix}moderation_notes (target, created)")));

    private static final String COLUMNS = "id, type, target, target_name, ip, actor, actor_name, reason, created, expires, "
            + "revoked_by, revoked_by_name, revoked_at, context";
    private static final String ACTIVE = "revoked_at IS NULL AND (expires IS NULL OR expires > ?)";

    private final Database db;
    private final String table;

    PunishmentStore(Database db) {
        this.db = db;
        this.table = db.table("moderation_punishments");
    }

    /** Last known address and exempt flag of a player, recorded at join. */
    record PlayerInfo(UUID uuid, String name, @Nullable String lastIp, boolean exempt) {}

    /** One address an account may log in from. */
    record LockedAddress(String address, String actorName, Instant created) {}

    // ---- writes ----

    /**
     * Inserts a punishment in one transaction, first revoking the active records it replaces: the same group
     * for account punishments, the same address for IP bans. Returns the stored record with its id.
     */
    CompletableFuture<Punishment> insert(Punishment p, Collection<PunishmentType> replaces) {
        return db.transaction(c -> {
            long now = p.created().toEpochMilli();
            if (!replaces.isEmpty() && !p.type().isInstant()) {
                if (p.type() == PunishmentType.IPBAN && p.ip() != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE " + table
                            + " SET revoked_by = ?, revoked_by_name = ?, revoked_at = ? WHERE type = ? AND ip = ? AND " + ACTIVE)) {
                        Database.bind(ps, uuid(p.actor()), p.actorName(), now, PunishmentType.IPBAN.name(), p.ip(), now);
                        ps.executeUpdate();
                    }
                } else if (p.target() != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE " + table
                            + " SET revoked_by = ?, revoked_by_name = ?, revoked_at = ? WHERE target = ? AND type IN ("
                            + placeholders(replaces.size()) + ") AND " + ACTIVE)) {
                        // Arrays.asList: the actor is null for the console.
                        List<Object> params = new ArrayList<>(java.util.Arrays.asList(uuid(p.actor()), p.actorName(), now,
                                p.target().toString()));
                        replaces.forEach(t -> params.add(t.name()));
                        params.add(now);
                        Database.bind(ps, params.toArray());
                        ps.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (type, target, target_name, ip, actor, "
                    + "actor_name, reason, created, expires, context) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, p.type().name());
                ps.setString(2, uuid(p.target()));
                ps.setString(3, p.targetName());
                ps.setString(4, p.ip());
                ps.setString(5, uuid(p.actor()));
                ps.setString(6, p.actorName());
                ps.setString(7, p.reason());
                ps.setLong(8, now);
                if (p.expires() == null) {
                    ps.setNull(9, Types.BIGINT);
                } else {
                    ps.setLong(9, p.expires().toEpochMilli());
                }
                ps.setString(10, p.context());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No id generated for new punishment");
                    }
                    long id = keys.getLong(1);
                    return new Punishment(id, p.type(), p.target(), p.targetName(), p.ip(), p.actor(), p.actorName(),
                            p.reason(), p.created(), p.expires(), null, null, null, p.context());
                }
            }
        });
    }

    /** Marks the given records revoked if they still are active. Returns the ids actually revoked. */
    CompletableFuture<List<Long>> revoke(List<Long> ids, @Nullable UUID by, String byName, Instant at) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return db.transaction(c -> {
            long now = at.toEpochMilli();
            List<Long> done = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table
                    + " SET revoked_by = ?, revoked_by_name = ?, revoked_at = ? WHERE id = ? AND " + ACTIVE)) {
                for (long id : ids) {
                    Database.bind(ps, uuid(by), byName, now, id, now);
                    if (ps.executeUpdate() > 0) {
                        done.add(id);
                    }
                }
            }
            return done;
        });
    }

    CompletableFuture<Void> recordPlayer(UUID uuid, String name, @Nullable String ip, boolean exempt) {
        String sql = db.dialect().upsert(db.table("moderation_players"), "uuid, name, last_ip, exempt, updated",
                "?, ?, ?, ?, ?", "uuid", "name, last_ip, exempt, updated");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), name, ip, exempt, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Stores where a jailed player should return to. Never overwrites an existing location, so escaping
     * the jail and being teleported back does not replace the original spot.
     */
    CompletableFuture<Void> saveReleaseLocation(UUID uuid, ReleaseLocation location) {
        String sql = db.dialect().upsert(db.table("moderation_release_locations"),
                "uuid, world, x, y, z, yaw, pitch", "?, ?, ?, ?, ?, ?, ?", "uuid", "uuid");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), location.world(), location.x(), location.y(), location.z(),
                        (double) location.yaw(), (double) location.pitch());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Whether a release location (or its unknown-location placeholder) is still stored. */
    CompletableFuture<Boolean> hasReleaseLocation(UUID uuid) {
        String table = db.table("moderation_release_locations");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Reads and deletes the stored release location in one transaction. */
    CompletableFuture<Optional<ReleaseLocation>> takeReleaseLocation(UUID uuid) {
        String table = db.table("moderation_release_locations");
        return db.transaction(c -> {
            ReleaseLocation found = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, yaw, pitch FROM " + table
                    + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        found = new ReleaseLocation(rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                                rs.getDouble(4), (float) rs.getDouble(5), (float) rs.getDouble(6));
                    }
                }
            }
            if (found != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            }
            return Optional.ofNullable(found);
        });
    }

    /**
     * Adds time to the active, timed jail sentence of an account. Completes with the number of rows changed:
     * a permanent or lifted sentence is left alone, and so is a jail that ran out meanwhile.
     */
    CompletableFuture<Integer> extendJail(UUID target, long millis, Instant now) {
        if (millis <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        return db.update("UPDATE " + table + " SET expires = expires + ? WHERE target = ? AND type = ? "
                + "AND revoked_at IS NULL AND expires IS NOT NULL AND expires > ?",
                millis, target.toString(), PunishmentType.JAIL.name(), now.toEpochMilli());
    }

    /** Remembers when a jailed account logged off, so the countdown can pause while it is away. */
    CompletableFuture<Void> pauseJail(UUID target, Instant at) {
        String sql = db.dialect().upsert(db.table("moderation_jail_pauses"), "uuid, paused_at", "?, ?", "uuid",
                "paused_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, target.toString(), at.toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Reads and deletes the logout marker of an account in one transaction. */
    CompletableFuture<Optional<Instant>> takeJailPause(UUID target) {
        String table = db.table("moderation_jail_pauses");
        return db.transaction(c -> {
            Instant found = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT paused_at FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        found = Instant.ofEpochMilli(rs.getLong(1));
                    }
                }
            }
            if (found != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                    ps.setString(1, target.toString());
                    ps.executeUpdate();
                }
            }
            return Optional.ofNullable(found);
        });
    }

    // ---- server-wide state (chat mute) ----

    CompletableFuture<Optional<MuteChatState>> loadState(String key) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT expires_at, reason, actor_name FROM "
                    + db.table("moderation_state") + " WHERE state_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<MuteChatState>empty();
                    }
                    long until = rs.getLong(1);
                    boolean hadUntil = !rs.wasNull();
                    return Optional.of(MuteChatState.decode(hadUntil ? Long.toString(until) : null,
                            rs.getString(2), rs.getString(3)));
                }
            }
        });
    }

    CompletableFuture<Void> saveState(String key, MuteChatState state) {
        String sql = db.dialect().upsert(db.table("moderation_state"), "state_key, expires_at, reason, actor_name",
                "?, ?, ?, ?", "state_key", "expires_at, reason, actor_name");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (state.until() == null) {
                    ps.setString(1, key);
                    ps.setNull(2, Types.BIGINT);
                    ps.setString(3, state.reason());
                    ps.setString(4, state.actorName());
                } else {
                    Database.bind(ps, key, state.until().toEpochMilli(), state.reason(), state.actorName());
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- login address locks ----

    /** Adds one allowed address (normalised by the caller); repeating it updates who added it. */
    CompletableFuture<Void> lockAddress(UUID uuid, String address, String actorName, Instant at) {
        String sql = db.dialect().upsert(db.table("moderation_lockip"),
                "uuid, address, actor_name, created", "?, ?, ?, ?", "uuid, address", "actor_name, created");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), address, actorName, at.toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes one allowed address. Returns whether a row was actually removed. */
    CompletableFuture<Boolean> unlockAddress(UUID uuid, String address) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("moderation_lockip")
                    + " WHERE uuid = ? AND address = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, address);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Removes every allowed address of an account. Returns how many were removed. */
    CompletableFuture<Integer> unlockAllAddresses(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("moderation_lockip")
                    + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    CompletableFuture<List<LockedAddress>> lockedAddresses(UUID uuid) {
        return db.query(c -> {
            List<LockedAddress> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT address, actor_name, created FROM "
                    + db.table("moderation_lockip") + " WHERE uuid = ? ORDER BY created, address")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new LockedAddress(rs.getString(1), rs.getString(2),
                                Instant.ofEpochMilli(rs.getLong(3))));
                    }
                }
            }
            return Collections.unmodifiableList(out);
        });
    }

    // ---- reads ----

    CompletableFuture<Optional<PlayerInfo>> playerInfo(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, last_ip, exempt FROM "
                    + db.table("moderation_players") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new PlayerInfo(uuid, rs.getString(1), rs.getString(2), rs.getBoolean(3)))
                            : Optional.<PlayerInfo>empty();
                }
            }
        });
    }

    /** Active records of one account. */
    CompletableFuture<List<Punishment>> active(UUID target, Instant now) {
        return select("WHERE target = ? AND " + ACTIVE + " ORDER BY id DESC", target.toString(), now.toEpochMilli());
    }

    /** Login check in one query: the account's active records plus every active IP ban. */
    CompletableFuture<List<Punishment>> loginCheck(UUID target, Instant now) {
        return select("WHERE " + ACTIVE + " AND (target = ? OR type = ?) ORDER BY id DESC",
                now.toEpochMilli(), target.toString(), PunishmentType.IPBAN.name());
    }

    CompletableFuture<List<Punishment>> activeIpBans(Instant now) {
        return select("WHERE type = ? AND " + ACTIVE + " ORDER BY id DESC", PunishmentType.IPBAN.name(), now.toEpochMilli());
    }

    /** Active records of the given kinds, newest first, one page. */
    CompletableFuture<List<Punishment>> activeByTypes(Set<PunishmentType> types, int offset, int limit) {
        if (types.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<Object> params = new ArrayList<>();
        params.add(Instant.now().toEpochMilli());
        types.forEach(t -> params.add(t.name()));
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));
        return select("WHERE " + ACTIVE + " AND type IN (" + placeholders(types.size())
                + ") ORDER BY id DESC LIMIT ? OFFSET ?", params.toArray());
    }

    CompletableFuture<Integer> countActive(Set<PunishmentType> types) {
        if (types.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        List<Object> params = new ArrayList<>();
        params.add(Instant.now().toEpochMilli());
        types.forEach(t -> params.add(t.name()));
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + ACTIVE + " AND type IN ("
                + placeholders(types.size()) + ")";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, params.toArray());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** How many records were stored for an exact address or range (used by /checkban). */
    CompletableFuture<Integer> countIp(String normalizedAddressOrRange) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE ip = ?")) {
                ps.setString(1, normalizedAddressOrRange);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    CompletableFuture<List<Punishment>> history(UUID target, int offset, int limit) {
        return select("WHERE target = ? ORDER BY id DESC LIMIT ? OFFSET ?", target.toString(), Math.max(1, limit),
                Math.max(0, offset));
    }

    CompletableFuture<Integer> count(UUID target) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE target = ?")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    CompletableFuture<Optional<Punishment>> byId(long id) {
        return select("WHERE id = ?", id).thenApply(list -> list.stream().findFirst());
    }

    // ---- editor writes (only active warnings) ----

    /** Changes the reason of any active record ({@code /checkban ... reason}). */
    CompletableFuture<Boolean> updateActiveReason(long id, String reason, Instant now) {
        return db.update("UPDATE " + table + " SET reason = ? WHERE id = ? AND revoked_at IS NULL "
                        + "AND (expires IS NULL OR expires > ?)",
                reason, id, now.toEpochMilli()).thenApply(rows -> rows > 0);
    }

    /** Changes the expiry of any active record ({@code null} makes it permanent). */
    CompletableFuture<Boolean> updateActiveExpiry(long id, @Nullable Instant expires, Instant now) {
        String sql = "UPDATE " + table + " SET expires = ? WHERE id = ? AND revoked_at IS NULL "
                + "AND (expires IS NULL OR expires > ?)";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (expires == null) {
                    ps.setNull(1, Types.BIGINT);
                } else {
                    ps.setLong(1, expires.toEpochMilli());
                }
                ps.setLong(2, id);
                ps.setLong(3, now.toEpochMilli());
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Changes the reason of an active warning. {@code false} when the record is gone, revoked or expired. */
    CompletableFuture<Boolean> updateWarningReason(long id, String reason, Instant now) {
        return db.update("UPDATE " + table + " SET reason = ? WHERE id = ? AND type = ? AND revoked_at IS NULL "
                        + "AND (expires IS NULL OR expires > ?)",
                reason, id, PunishmentType.WARN.name(), now.toEpochMilli()).thenApply(rows -> rows > 0);
    }

    /** Changes the expiry of an active warning ({@code null} makes it permanent). */
    CompletableFuture<Boolean> updateWarningExpiry(long id, @Nullable Instant expires, Instant now) {
        String sql = "UPDATE " + table + " SET expires = ? WHERE id = ? AND type = ? AND revoked_at IS NULL "
                + "AND (expires IS NULL OR expires > ?)";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (expires == null) {
                    ps.setNull(1, Types.BIGINT);
                } else {
                    ps.setLong(1, expires.toEpochMilli());
                }
                ps.setLong(2, id);
                ps.setString(3, PunishmentType.WARN.name());
                ps.setLong(4, now.toEpochMilli());
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** How many players are currently jailed in one jail (used by {@code /jailedit <jail> info}). */
    CompletableFuture<Integer> countActiveJail(String jailName, Instant now) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE type = ? "
                    + "AND context = ? AND " + ACTIVE)) {
                Database.bind(ps, PunishmentType.JAIL.name(), jailName, now.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private CompletableFuture<List<Punishment>> select(String where, Object... params) {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " " + where;
        return db.query(c -> read(c, sql, params));
    }

    private static List<Punishment> read(Connection c, String sql, Object... params) throws SQLException {
        List<Punishment> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Database.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PunishmentType type;
                    try {
                        type = PunishmentType.valueOf(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        continue; // written by a newer version; never guess what it means
                    }
                    out.add(new Punishment(rs.getLong(1), type, parseUuid(rs.getString(3)), rs.getString(4),
                            rs.getString(5), parseUuid(rs.getString(6)), rs.getString(7), rs.getString(8),
                            Instant.ofEpochMilli(rs.getLong(9)), instant(rs, 10), parseUuid(rs.getString(11)),
                            rs.getString(12), instant(rs, 13), rs.getString(14)));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static @Nullable Instant instant(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static @Nullable UUID parseUuid(@Nullable String text) {
        return text == null ? null : UUID.fromString(text);
    }

    private static @Nullable String uuid(@Nullable UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }
}
