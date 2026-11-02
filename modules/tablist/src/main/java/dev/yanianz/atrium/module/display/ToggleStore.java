package dev.yanianz.atrium.module.display;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A persisted per-player "hidden" flag (used by /scoreboard toggle and /bossbar toggle), stored in
 * {@code {prefix}<module>_prefs} and exposed as a user component. Only players who toggled have a row.
 */
public final class ToggleStore {
    /** Immutable component value. */
    public record Toggle(boolean hidden) {
        public static final Toggle DEFAULT = new Toggle(false);
    }

    private final Database db;
    private final String table;
    public final UserComponentType<Toggle> component;

    public ToggleStore(Database db, String module) {
        this.db = db;
        this.table = module + "_prefs";
        this.component = new UserComponentType<>(module + ":toggle", this::load, this::save, () -> Toggle.DEFAULT);
    }

    /** Migrations for {@code {prefix}<module>_prefs}. */
    public static List<Migration> migrations(String module) {
        return List.of(Migration.of(1, module + "_prefs",
                "CREATE TABLE {prefix}" + module + "_prefs (uuid CHAR(36) NOT NULL PRIMARY KEY, hidden BOOLEAN NOT NULL)"));
    }

    private CompletableFuture<Toggle> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT hidden FROM " + db.table(table) + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Toggle(rs.getBoolean(1)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, Toggle value) {
        String sql = db.dialect().upsert(db.table(table), "uuid, hidden", "?, ?", "uuid", "hidden");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), value.hidden());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
