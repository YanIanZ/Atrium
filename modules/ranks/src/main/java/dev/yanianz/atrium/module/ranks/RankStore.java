package dev.yanianz.atrium.module.ranks;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Per-player rank plus the set of ranks whose commands were already delivered. */
final class RankStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "ranks player",
            "CREATE TABLE {prefix}ranks_player (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "rank_id VARCHAR(32) NOT NULL, rewarded TEXT NOT NULL)"));

    private final Database db;
    private final Supplier<String> startingRank;
    final UserComponentType<RankProfile> component;

    RankStore(Database db, Supplier<String> startingRank) {
        this.db = db;
        this.startingRank = startingRank;
        this.component = new UserComponentType<>("ranks:profile", this::load, this::save,
                () -> RankProfile.initial(this.startingRank.get()));
    }

    private CompletableFuture<RankProfile> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rank_id, rewarded FROM " + db.table("ranks_player") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new RankProfile(rs.getString(1), parseRewarded(rs.getString(2)));
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, RankProfile profile) {
        String sql = db.dialect().upsert(db.table("ranks_player"), "uuid, rank_id, rewarded", "?, ?, ?", "uuid",
                "rank_id, rewarded");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), profile.rankId(), joinRewarded(profile.rewardedRanks()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Stored rank for a player who may be offline, without loading the whole user. */
    CompletableFuture<String> storedRank(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rank_id FROM " + db.table("ranks_player") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : startingRank.get();
                }
            }
        });
    }

    private static Set<String> parseRewarded(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            String id = part.strip();
            if (!id.isEmpty()) {
                result.add(id);
            }
        }
        return result;
    }

    private static String joinRewarded(Set<String> rewarded) {
        return rewarded.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
    }
}
