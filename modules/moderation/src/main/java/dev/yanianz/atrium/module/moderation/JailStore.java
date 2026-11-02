package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/** Jail definitions: stored in {@code {prefix}moderation_jails}, mirrored in memory for movement checks. */
final class JailStore {
    /** The schema version that added the per-jail editor options. */
    static final int EDITOR_COLUMNS_VERSION = 4;

    private static final String COLUMNS = "name, world, x, y, z, yaw, pitch, radius, release_world, release_x, "
            + "release_y, release_z, release_yaw, release_pitch, allowed_commands, max_sentence, chat_allowed";
    private static final String PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
    private static final String UPDATE_SET = "world, x, y, z, yaw, pitch, radius, release_world, release_x, "
            + "release_y, release_z, release_yaw, release_pitch, allowed_commands, max_sentence, chat_allowed";

    private final Database db;
    private final Map<String, Jail> jails = new ConcurrentHashMap<>();
    /** One monitor per jail name: serialises read-modify-write cycles so concurrent edits add up. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    JailStore(Database db) {
        this.db = db;
    }

    /**
     * Applies the v4 "per-jail editor options" step idempotently.
     *
     * <p>v4 was designed as nine separate {@code ALTER TABLE} statements. On MySQL each DDL statement
     * auto-commits, so a failure half-way leaves some columns behind without the version bump; every
     * restart then fails with "duplicate column". {@link dev.yanianz.atrium.core.storage.Migration} only
     * yields SQL and cannot inspect the table, so the step is applied here instead: existing columns are
     * skipped, the missing ones are added, then v4 is recorded. On MySQL all additions happen in ONE
     * {@code ALTER TABLE} (atomic DDL); SQLite keeps one statement per column as it requires.
     */
    static CompletableFuture<Void> ensureEditorColumns(Database db) {
        return db.transaction(connection -> {
            if (schemaVersion(connection, db) >= EDITOR_COLUMNS_VERSION) {
                return null;
            }
            String table = db.table("moderation_jails");
            Set<String> existing = columns(connection, table);
            List<String> missing = editorColumns(db.dialect()).keySet().stream()
                    .filter(name -> !existing.contains(name.toLowerCase(Locale.ROOT)))
                    .toList();
            for (String statement : alterStatements(db.dialect(), table, missing)) {
                try (Statement s = connection.createStatement()) {
                    s.executeUpdate(statement);
                }
            }
            recordVersion(connection, db);
            return null;
        });
    }

    /** Column name to definition, in the order the columns are added. */
    static Map<String, String> editorColumns(Dialect dialect) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("release_world", "VARCHAR(64)");
        columns.put("release_x", "DOUBLE");
        columns.put("release_y", "DOUBLE");
        columns.put("release_z", "DOUBLE");
        columns.put("release_yaw", "DOUBLE");
        columns.put("release_pitch", "DOUBLE");
        columns.put("allowed_commands", "VARCHAR(512)");
        columns.put("max_sentence", "BIGINT");
        columns.put("chat_allowed", "BOOLEAN NOT NULL DEFAULT " + (dialect == Dialect.SQLITE ? "1" : "TRUE"));
        return columns;
    }

    /** The ALTER statements that add {@code missing} columns; empty when there is nothing to add. */
    static List<String> alterStatements(Dialect dialect, String table, List<String> missing) {
        if (missing.isEmpty()) {
            return List.of();
        }
        Map<String, String> definitions = editorColumns(dialect);
        List<String> clauses = missing.stream()
                .map(name -> "ADD COLUMN " + name + " " + definitions.get(name))
                .toList();
        if (dialect == Dialect.MYSQL) {
            return List.of("ALTER TABLE " + table + " " + String.join(", ", clauses));
        }
        return clauses.stream().map(clause -> "ALTER TABLE " + table + " " + clause).toList();
    }

    private static int schemaVersion(Connection connection, Database db) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT version FROM " + db.table("schema_versions") + " WHERE namespace = ?")) {
            ps.setString(1, "moderation");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void recordVersion(Connection connection, Database db) throws SQLException {
        String sql = db.dialect().upsert(db.table("schema_versions"), "namespace, version", "?, ?",
                "namespace", "version");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "moderation");
            ps.setInt(2, EDITOR_COLUMNS_VERSION);
            ps.executeUpdate();
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    CompletableFuture<Void> load() {
        return db.query(c -> {
            List<Jail> loaded = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM "
                    + db.table("moderation_jails")); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loaded.add(read(rs));
                }
            }
            return loaded;
        }).thenAccept(loaded -> {
            jails.clear();
            loaded.forEach(j -> jails.put(key(j.name()), j));
        });
    }

    private static Jail read(ResultSet rs) throws java.sql.SQLException {
        String releaseWorld = rs.getString(9);
        ReleaseLocation release = releaseWorld == null ? null : new ReleaseLocation(releaseWorld, rs.getDouble(10),
                rs.getDouble(11), rs.getDouble(12), (float) rs.getDouble(13), (float) rs.getDouble(14));
        long maxSeconds = rs.getLong(16);
        Duration maxSentence = rs.wasNull() ? null : Duration.ofSeconds(maxSeconds);
        return new Jail(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                rs.getFloat(6), rs.getFloat(7), rs.getDouble(8), release, decodeCommands(rs.getString(15)), maxSentence,
                rs.getBoolean(17));
    }

    /**
     * Read-modify-write of one jail under its own lock. The editor sees the current definition, the row is
     * written and the cache updated before the lock is released, so concurrent edits cannot lose each
     * other and the cache never holds a value older than the database. Returns empty when the jail was
     * deleted while the edit was waiting (a delete removes the cached entry first, so a late edit cannot
     * resurrect it).
     */
    CompletableFuture<Optional<Jail>> update(String name, UnaryOperator<Jail> editor) {
        String key = key(name);
        return db.query(c -> {
            synchronized (lock(key)) {
                Jail current = jails.get(key);
                if (current == null) {
                    return Optional.<Jail>empty();
                }
                Jail next = editor.apply(current);
                if (next == null) {
                    return Optional.<Jail>empty();
                }
                Jail stored = normalise(next, key);
                upsert(c, stored);
                jails.put(key, stored);
                return Optional.of(stored);
            }
        });
    }

    /** Full write (new jail, or a replace that already preserved the editor options). */
    CompletableFuture<Void> save(Jail jail) {
        String key = key(jail.name());
        Jail stored = normalise(jail, key);
        return db.query(c -> {
            synchronized (lock(key)) {
                upsert(c, stored);
                jails.put(key, stored);
                return null;
            }
        });
    }

    private void upsert(Connection c, Jail jail) throws SQLException {
        String sql = db.dialect().upsert(db.table("moderation_jails"), COLUMNS, PLACEHOLDERS, "name", UPDATE_SET);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ReleaseLocation release = jail.release();
            Database.bind(ps, key(jail.name()), jail.world(), jail.x(), jail.y(), jail.z(), (double) jail.yaw(),
                    (double) jail.pitch(), jail.radius(),
                    release == null ? null : release.world(),
                    release == null ? null : release.x(),
                    release == null ? null : release.y(),
                    release == null ? null : release.z(),
                    release == null ? null : (double) release.yaw(),
                    release == null ? null : (double) release.pitch(),
                    encodeCommands(jail.allowedCommands()),
                    jail.maxSentence() == null ? null : jail.maxSentence().toSeconds(),
                    jail.chatAllowed());
            ps.executeUpdate();
        }
    }

    CompletableFuture<Boolean> delete(String name) {
        String key = key(name);
        return db.query(c -> {
            synchronized (lock(key)) {
                // Remove the cached entry first: whatever was waiting on this lock must not see (and
                // write back) a jail that no longer exists.
                Jail removed = jails.remove(key);
                int rows;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("moderation_jails")
                        + " WHERE name = ?")) {
                    ps.setString(1, key);
                    rows = ps.executeUpdate();
                }
                return rows > 0 || removed != null;
            }
        });
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    private static Jail normalise(Jail jail, String key) {
        return new Jail(key, jail.world(), jail.x(), jail.y(), jail.z(), jail.yaw(), jail.pitch(), jail.radius(),
                jail.release(), jail.allowedCommands(), jail.maxSentence(), jail.chatAllowed());
    }

    Optional<Jail> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(jails.get(key(name)));
    }

    /** The named jail, else the configured default, else any jail. */
    Optional<Jail> resolve(String name, String defaultName) {
        return get(name).or(() -> get(defaultName)).or(() -> jails.values().stream().findFirst());
    }

    Collection<Jail> all() {
        return List.copyOf(jails.values());
    }

    Collection<String> names() {
        return jails.values().stream().map(Jail::name).sorted().toList();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Comma separated in storage: entries can never contain a comma (see {@link EditorInput#allowedCommand}). */
    private static String encodeCommands(List<String> commands) {
        return String.join(",", commands);
    }

    private static List<String> decodeCommands(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(",")).filter(s -> !s.isBlank()).toList();
    }
}
