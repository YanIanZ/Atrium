package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable audit trail of ground cleanup runs. Never on the region thread. */
final class GroundCleanStore {
    record RunRecord(String id, long ranAt, String trigger, String actor, String world, int items, int orbs,
            int whitelisted, int protectedCount, int chunks, long durationMs) {}

    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "performance groundclean history",
            "CREATE TABLE {prefix}performance_groundclean (id CHAR(36) NOT NULL PRIMARY KEY,"
                    + " ran_at BIGINT NOT NULL, trigger_name VARCHAR(16) NOT NULL, actor VARCHAR(64) NOT NULL,"
                    + " world VARCHAR(128) NOT NULL, items INT NOT NULL, orbs INT NOT NULL,"
                    + " whitelisted INT NOT NULL, protected_count INT NOT NULL, chunks INT NOT NULL,"
                    + " duration_ms BIGINT NOT NULL)",
            "CREATE INDEX {prefix}performance_groundclean_time ON {prefix}performance_groundclean (ran_at)"));

    private final Database db;

    GroundCleanStore(Database db) {
        this.db = db;
    }

    CompletableFuture<Void> record(RunRecord run) {
        return db.update("INSERT INTO {prefix}performance_groundclean (id, ran_at, trigger_name, actor, world,"
                        + " items, orbs, whitelisted, protected_count, chunks, duration_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run.id(), run.ranAt(), run.trigger(), run.actor(), run.world(), run.items(), run.orbs(),
                run.whitelisted(), run.protectedCount(), run.chunks(), run.durationMs())
                .thenApply(ignored -> null);
    }

    CompletableFuture<List<RunRecord>> recent(int limit, int offset) {
        return db.query(c -> {
            List<RunRecord> runs = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, ran_at, trigger_name, actor, world, items,"
                    + " orbs, whitelisted, protected_count, chunks, duration_ms FROM "
                    + db.table("performance_groundclean") + " ORDER BY ran_at DESC, id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        runs.add(new RunRecord(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                                rs.getInt(10), rs.getLong(11)));
                    }
                }
            }
            return List.copyOf(runs);
        });
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
