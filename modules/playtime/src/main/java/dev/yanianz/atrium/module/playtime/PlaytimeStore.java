package dev.yanianz.atrium.module.playtime;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Per-day playtime buckets, cached leaderboards and durable reward claim watermarks. */
final class PlaytimeStore {
    static final List<Migration> MIGRATIONS = List.of(
            Migration.of(1, "playtime daily buckets",
                    "CREATE TABLE {prefix}playtime_daily (uuid CHAR(36) NOT NULL, day CHAR(10) NOT NULL, "
                            + "seconds BIGINT NOT NULL, PRIMARY KEY (uuid, day))",
                    "CREATE INDEX {prefix}playtime_daily_day ON {prefix}playtime_daily (day)"),
            Migration.of(2, "playtime reward claims",
                    "CREATE TABLE {prefix}playtime_claims (uuid CHAR(36) NOT NULL, reward_id VARCHAR(64) NOT NULL, "
                            + "state VARCHAR(16) NOT NULL, watermark BIGINT NOT NULL, updated BIGINT NOT NULL, "
                            + "PRIMARY KEY (uuid, reward_id))"),
            // Per-step delivery progress: a retried claim resumes where it stopped instead of paying the
            // same commands (or the same money) again.
            Migration.of(3, "playtime reward delivery progress",
                    "ALTER TABLE {prefix}playtime_claims ADD COLUMN money_done INT NOT NULL DEFAULT 0",
                    "ALTER TABLE {prefix}playtime_claims ADD COLUMN commands_done INT NOT NULL DEFAULT 0"));

    record TopEntry(UUID uuid, String name, long seconds) {}

    record PendingClaim(String rewardId, long watermark) {}

    /** Which delivery steps of one claim already ran. */
    record Progress(boolean moneyDone, int commandsDone) {
        static final Progress EMPTY = new Progress(false, 0);
    }

    /** Result of claiming a watermark: the progress of a claim that still has to be delivered. */
    record Claim(long watermark, Progress progress) {}

    private final Database db;
    final UserComponentType<PlaytimeProfile> component;

    PlaytimeStore(Database db) {
        this.db = db;
        // Claims are written to their own table before and after each delivery, so this component is a
        // read-through cache: a late component save must never overwrite a newer PENDING marker.
        this.component = new UserComponentType<>("playtime:profile", this::loadProfile,
                (uuid, profile) -> CompletableFuture.completedFuture(null), () -> PlaytimeProfile.EMPTY);
    }

    private CompletableFuture<PlaytimeProfile> loadProfile(UUID uuid) {
        return db.query(c -> {
            Map<String, Long> marks = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT reward_id, watermark FROM "
                    + db.table("playtime_claims") + " WHERE uuid = ? AND state = 'DONE'")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        marks.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return new PlaytimeProfile(marks);
        });
    }

    CompletableFuture<Long> total(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(seconds), 0) FROM " + db.table("playtime_daily") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    CompletableFuture<Long> secondsOn(UUID uuid, LocalDate day) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT seconds FROM " + db.table("playtime_daily")
                    + " WHERE uuid = ? AND day = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, day.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** Day buckets in {@code [from, to]}, used by the /cplaytime calendar. */
    CompletableFuture<Map<LocalDate, Long>> range(UUID uuid, LocalDate from, LocalDate to) {
        return db.query(c -> {
            Map<LocalDate, Long> days = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT day, seconds FROM "
                    + db.table("playtime_daily") + " WHERE uuid = ? AND day >= ? AND day <= ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, from.toString());
                ps.setString(3, to.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            days.put(LocalDate.parse(rs.getString(1)), rs.getLong(2));
                        } catch (java.time.format.DateTimeParseException e) {
                            // A hand-edited row with a broken date must not break the whole calendar.
                        }
                    }
                }
            }
            return Map.copyOf(days);
        });
    }

    /** Atomically adds active seconds to one day bucket. */
    CompletableFuture<Void> addSeconds(UUID uuid, LocalDate day, long seconds) {
        if (seconds <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        String table = db.table("playtime_daily");
        String sql = db.dialect() == Dialect.SQLITE
                ? "INSERT INTO " + table + " (uuid, day, seconds) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid, day) DO UPDATE SET seconds = seconds + excluded.seconds"
                : "INSERT INTO " + table + " (uuid, day, seconds) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE seconds = seconds + VALUES(seconds)";
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), day.toString(), seconds);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Replaces every stored day bucket of {@code uuid} with one bucket for {@code day}. Used by the
     * importer: the daily history cannot be reconstructed, but the total (all-time and leaderboards)
     * is exact. Zero removes all buckets.
     */
    CompletableFuture<Void> setTotal(UUID uuid, LocalDate day, long seconds) {
        String table = db.table("playtime_daily");
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            if (seconds > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + table + " (uuid, day, seconds) VALUES (?, ?, ?)")) {
                    Database.bind(ps, uuid.toString(), day.toString(), seconds);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Top players from {@code from} (inclusive), or all time when {@code from} is null. */
    CompletableFuture<List<TopEntry>> top(LocalDate from, int limit) {
        String filter = from == null ? "" : " WHERE d.day >= ?";
        String sql = "SELECT d.uuid, u.name, SUM(d.seconds) AS s FROM " + db.table("playtime_daily") + " d "
                + "LEFT JOIN " + db.table("users") + " u ON u.uuid = d.uuid" + filter
                + " GROUP BY d.uuid, u.name ORDER BY s DESC, d.uuid ASC LIMIT ?";
        return db.query(c -> {
            List<TopEntry> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int index = 1;
                if (from != null) {
                    ps.setString(index++, from.toString());
                    ps.setInt(index++, limit);
                } else {
                    // MySQL treats "LIMIT ?" fine, but keeping one placeholder path documents the order.
                    ps.setInt(index, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString(1));
                        String name = rs.getString(2);
                        result.add(new TopEntry(uuid, name == null ? uuid.toString() : name, rs.getLong(3)));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    CompletableFuture<List<PendingClaim>> pending(UUID uuid) {
        return db.query(c -> {
            List<PendingClaim> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT reward_id, watermark FROM "
                    + db.table("playtime_claims") + " WHERE uuid = ? AND state = 'PENDING'")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new PendingClaim(rs.getString(1), rs.getLong(2)));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    /**
     * Claims one watermark. Empty when this watermark was already delivered (or a newer claim exists).
     * A claim that is retried with the same watermark keeps the progress it already made.
     */
    CompletableFuture<Optional<Claim>> beginClaim(UUID uuid, String rewardId, long watermark) {
        String table = db.table("playtime_claims");
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state, watermark, money_done, commands_done FROM "
                    + table + " WHERE uuid = ? AND reward_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, rewardId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String state = rs.getString(1);
                        long current = rs.getLong(2);
                        boolean moneyDone = rs.getInt(3) != 0;
                        int commandsDone = rs.getInt(4);
                        if ("DONE".equals(state) && current >= watermark) {
                            return Optional.<Claim>empty();
                        }
                        if (current == watermark) {
                            // Retry of the same (still PENDING) claim: never restart completed steps.
                            return Optional.of(new Claim(watermark, new Progress(moneyDone, commandsDone)));
                        }
                        if (current > watermark) {
                            // A newer claim already owns the row; an older mark must never regress it.
                            return Optional.<Claim>empty();
                        }
                    }
                }
            }
            String upsert = db.dialect().upsert(table,
                    "uuid, reward_id, state, watermark, updated, money_done, commands_done",
                    "?, ?, ?, ?, ?, ?, ?", "uuid, reward_id",
                    "state, watermark, updated, money_done, commands_done");
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                Database.bind(ps, uuid.toString(), rewardId, "PENDING", watermark, System.currentTimeMillis(), 0, 0);
                ps.executeUpdate();
            }
            return Optional.of(new Claim(watermark, Progress.EMPTY));
        });
    }

    /** Records that the money step of the given claim already ran (paid or permanently skipped). */
    CompletableFuture<Void> markMoneyDone(UUID uuid, String rewardId, long watermark) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("playtime_claims")
                    + " SET money_done = 1, updated = ? WHERE uuid = ? AND reward_id = ? AND state = 'PENDING' "
                    + "AND watermark = ?")) {
                Database.bind(ps, System.currentTimeMillis(), uuid.toString(), rewardId, watermark);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Records how many leading commands of the given claim were dispatched. Never moves backwards. */
    CompletableFuture<Void> markCommandsDone(UUID uuid, String rewardId, long watermark, int commandsDone) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("playtime_claims")
                    + " SET commands_done = ?, updated = ? WHERE uuid = ? AND reward_id = ? AND state = 'PENDING' "
                    + "AND watermark = ? AND commands_done < ?")) {
                Database.bind(ps, commandsDone, System.currentTimeMillis(), uuid.toString(), rewardId, watermark,
                        commandsDone);
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> completeClaim(UUID uuid, String rewardId, long watermark) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("playtime_claims")
                    + " SET state = 'DONE', updated = ? WHERE uuid = ? AND reward_id = ? AND state = 'PENDING' "
                    + "AND watermark = ?")) {
                Database.bind(ps, System.currentTimeMillis(), uuid.toString(), rewardId, watermark);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes a pending marker whose delivery failed permanently, so a later check can retry. */
    CompletableFuture<Void> releaseClaim(UUID uuid, String rewardId, long watermark) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("playtime_claims")
                    + " WHERE uuid = ? AND reward_id = ? AND state = 'PENDING' AND watermark = ?")) {
                Database.bind(ps, uuid.toString(), rewardId, watermark);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Persists a baseline (no delivery), never downgrading a newer DONE or PENDING marker. */
    CompletableFuture<Void> markDone(UUID uuid, String rewardId, long watermark) {
        String table = db.table("playtime_claims");
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state, watermark FROM " + table
                    + " WHERE uuid = ? AND reward_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, rewardId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String state = rs.getString(1);
                        long current = rs.getLong(2);
                        if ("PENDING".equals(state) || current >= watermark) {
                            return null;
                        }
                    }
                }
            }
            String upsert = db.dialect().upsert(table, "uuid, reward_id, state, watermark, updated",
                    "?, ?, ?, ?, ?", "uuid, reward_id", "state, watermark, updated");
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                Database.bind(ps, uuid.toString(), rewardId, "DONE", watermark, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Moves a watermark forward without paying, used after an administrative total change.
     *
     * <p>Unlike {@link #markDone}, this also cancels a PENDING claim whose mark is now below the new
     * watermark: that delivery belongs to the interval range the administrator just skipped, and keeping
     * it would let the retry pay it and then compute a fresh backlog from its old mark. A PENDING claim at
     * or above the target is left alone (nothing is owed for it and cancelling could lose a delivery).
     * Watermarks never move backwards.
     */
    CompletableFuture<Void> advanceTo(UUID uuid, String rewardId, long watermark) {
        if (watermark <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        String table = db.table("playtime_claims");
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table
                    + " WHERE uuid = ? AND reward_id = ? AND state = 'PENDING' AND watermark < ?")) {
                Database.bind(ps, uuid.toString(), rewardId, watermark);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT state, watermark FROM " + table
                    + " WHERE uuid = ? AND reward_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, rewardId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(2) >= watermark) {
                        return null;
                    }
                }
            }
            String upsert = db.dialect().upsert(table, "uuid, reward_id, state, watermark, updated",
                    "?, ?, ?, ?, ?", "uuid, reward_id", "state, watermark, updated");
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                Database.bind(ps, uuid.toString(), rewardId, "DONE", watermark, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
