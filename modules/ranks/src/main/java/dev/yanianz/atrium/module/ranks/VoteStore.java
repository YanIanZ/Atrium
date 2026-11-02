package dev.yanianz.atrium.module.ranks;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Vote counts keyed by the lower-case player name. Votifier reports a name, not a uuid, so the table stays
 * usable for players who have never joined Atrium; the first-seen capitalisation is kept for display.
 */
final class VoteStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(2, "vote counts",
            "CREATE TABLE {prefix}ranks_votes (player VARCHAR(16) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                    + "votes BIGINT NOT NULL, last BIGINT NOT NULL)"));

    record Entry(String name, long votes, long last) {}

    private final Database db;

    VoteStore(Database db) {
        this.db = db;
    }

    /** Adds one vote from Votifier; a new name starts at one. */
    CompletableFuture<Long> increment(String displayName) {
        Optional<String> key = VoteMath.normalizeName(displayName);
        if (key.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid vote name"));
        }
        String table = db.table("ranks_votes");
        return db.transaction(connection -> {
            long current = 0L;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT votes FROM " + table + " WHERE player = ?")) {
                ps.setString(1, key.get());
                try (ResultSet rows = ps.executeQuery()) {
                    if (rows.next()) {
                        current = rows.getLong(1);
                    }
                }
            }
            long next = VoteMath.add(current, 1L);
            String sql = db.dialect().upsert(table, "player, name, votes, last", "?, ?, ?, ?", "player",
                    "name, votes, last");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Database.bind(ps, key.get(), displayName.strip(), next, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return next;
        });
    }

    CompletableFuture<Optional<Entry>> get(String displayName) {
        Optional<String> key = VoteMath.normalizeName(displayName);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name, votes, last FROM " + db.table("ranks_votes") + " WHERE player = ?")) {
                ps.setString(1, key.get());
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.<Entry>empty();
                    }
                    return Optional.of(new Entry(rows.getString(1), rows.getLong(2), rows.getLong(3)));
                }
            }
        });
    }

    CompletableFuture<Void> set(String displayName, long votes) {
        if (votes <= 0) {
            return clear(displayName).thenApply(ignored -> null);
        }
        Optional<String> key = VoteMath.normalizeName(displayName);
        if (key.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid vote name"));
        }
        String table = db.table("ranks_votes");
        String sql = db.dialect().upsert(table, "player, name, votes, last", "?, ?, ?, ?", "player",
                "name, votes, last");
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Database.bind(ps, key.get(), displayName.strip(), VoteMath.set(votes), System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Atomically adds ({@code amount > 0}) or takes ({@code amount < 0}) votes. */
    CompletableFuture<Long> change(String displayName, long amount) {
        Optional<String> key = VoteMath.normalizeName(displayName);
        if (key.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid vote name"));
        }
        String table = db.table("ranks_votes");
        return db.transaction(connection -> {
            long current = 0L;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT votes FROM " + table + " WHERE player = ?")) {
                ps.setString(1, key.get());
                try (ResultSet rows = ps.executeQuery()) {
                    if (rows.next()) {
                        current = rows.getLong(1);
                    }
                }
            }
            long next = amount >= 0 ? VoteMath.add(current, amount) : VoteMath.take(current, -amount);
            String sql = db.dialect().upsert(table, "player, name, votes, last", "?, ?, ?, ?", "player",
                    "name, votes, last");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                Database.bind(ps, key.get(), displayName.strip(), next, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return next;
        });
    }

    CompletableFuture<Integer> clear(String displayName) {
        Optional<String> key = VoteMath.normalizeName(displayName);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + db.table("ranks_votes") + " WHERE player = ?")) {
                ps.setString(1, key.get());
                return ps.executeUpdate();
            }
        });
    }

    CompletableFuture<Long> count() {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + db.table("ranks_votes"));
                    ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        });
    }

    CompletableFuture<List<Entry>> top(int offset, int limit) {
        return db.query(connection -> {
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT name, votes, last FROM "
                    + db.table("ranks_votes") + " ORDER BY votes DESC, player ASC LIMIT ? OFFSET ?")) {
                ps.setInt(1, Math.max(1, limit));
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        entries.add(new Entry(rows.getString(1), rows.getLong(2), rows.getLong(3)));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }
}
