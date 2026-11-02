package dev.yanianz.atrium.module.playerplus;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Storage of {@link PlayerPlusState} in its own table, exposed as a user component. */
final class PlayerPlusStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "playerplus_state",
            "CREATE TABLE {prefix}playerplus_state ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "tfly_remaining_ms BIGINT NOT NULL DEFAULT 0, "
                    + "flight_charge BIGINT NOT NULL DEFAULT 0, "
                    + "auto_recharge BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "cuffed BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "cuff_world VARCHAR(64), "
                    + "cuff_x DOUBLE NOT NULL DEFAULT 0, "
                    + "cuff_y DOUBLE NOT NULL DEFAULT 0, "
                    + "cuff_z DOUBLE NOT NULL DEFAULT 0, "
                    + "cuff_by VARCHAR(64), "
                    + "cuff_since BIGINT NOT NULL DEFAULT 0)"));

    private static final String COLUMNS = "uuid, tfly_remaining_ms, flight_charge, auto_recharge, cuffed, "
            + "cuff_world, cuff_x, cuff_y, cuff_z, cuff_by, cuff_since";
    private static final String PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
    private static final String UPDATES = "tfly_remaining_ms, flight_charge, auto_recharge, cuffed, "
            + "cuff_world, cuff_x, cuff_y, cuff_z, cuff_by, cuff_since";

    private final Database db;
    final UserComponentType<PlayerPlusState> component;

    PlayerPlusStore(Database db, Supplier<PlayerPlusState> defaults) {
        this.db = db;
        this.component = new UserComponentType<>("playerplus:state", this::load, this::save, defaults);
    }

    private CompletableFuture<PlayerPlusState> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM " + db.table("playerplus_state") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    CuffPoint cuff = null;
                    if (rs.getBoolean("cuffed")) {
                        cuff = new CuffPoint(
                                nullToEmpty(rs.getString("cuff_world")),
                                rs.getDouble("cuff_x"),
                                rs.getDouble("cuff_y"),
                                rs.getDouble("cuff_z"),
                                nullToEmpty(rs.getString("cuff_by")),
                                rs.getLong("cuff_since"));
                    }
                    return new PlayerPlusState(rs.getLong("tfly_remaining_ms"), rs.getLong("flight_charge"),
                            rs.getBoolean("auto_recharge"), cuff);
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, PlayerPlusState state) {
        String sql = db.dialect().upsert(db.table("playerplus_state"), COLUMNS, PLACEHOLDERS, "uuid", UPDATES);
        CuffPoint cuff = state.cuff();
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps,
                        uuid.toString(),
                        state.tflyRemainingMillis(),
                        state.flightChargeSeconds(),
                        state.autoRecharge(),
                        cuff != null,
                        cuff == null ? null : cuff.world(),
                        cuff == null ? 0.0 : cuff.x(),
                        cuff == null ? 0.0 : cuff.y(),
                        cuff == null ? 0.0 : cuff.z(),
                        cuff == null ? null : cuff.by(),
                        cuff == null ? 0L : cuff.since());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
