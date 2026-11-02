package dev.yanianz.atrium.module.rtp;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Table of the rtp module: persisted per-world cooldowns. */
final class RtpStore {
    static final List<Migration> MIGRATIONS = List.of(
            Migration.of(1, "rtp_cooldowns",
                    "CREATE TABLE {prefix}rtp_cooldowns (uuid CHAR(36) NOT NULL, world VARCHAR(96) NOT NULL, "
                            + "expires_at BIGINT NOT NULL, PRIMARY KEY (uuid, world))"));

    record CooldownRow(UUID player, String world, long expiresAt) {}

    private final Database db;

    RtpStore(Database db) {
        this.db = db;
    }

    /** Cooldowns that have not ended at {@code now} (epoch millis). */
    CompletableFuture<List<CooldownRow>> loadCooldowns(long now) {
        return db.query(c -> {
            List<CooldownRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, world, expires_at FROM "
                    + db.table("rtp_cooldowns") + " WHERE expires_at > ?")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            rows.add(new CooldownRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                        } catch (IllegalArgumentException e) {
                            // malformed uuid: skip the row
                        }
                    }
                }
            }
            return rows;
        });
    }

    CompletableFuture<Void> saveCooldown(UUID player, String world, long expiresAt) {
        String sql = db.dialect().upsert(db.table("rtp_cooldowns"), "uuid, world, expires_at", "?, ?, ?",
                "uuid, world", "expires_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, player.toString(), RtpCooldowns.worldKey(world), expiresAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Integer> purge(long now) {
        return db.update("DELETE FROM {prefix}rtp_cooldowns WHERE expires_at <= ?", now);
    }
}
