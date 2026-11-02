package dev.yanianz.atrium.module.inspect;

import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.storage.Database;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Deletes only Atrium's own rows for one account. Tables are discovered from the JDBC metadata (the
 * configured prefix decides the namespace) and every column that stores an account id is matched, so a new
 * module table is covered without editing this class. Nothing outside the configured prefix is touched.
 *
 * <p>A loaded user must never be deleted: the in-memory copy would be saved back a moment later. Callers
 * therefore refuse when the account is online or still in the offline cache.
 */
final class UserDataAdmin {
    /** Account-id columns Atrium uses. Matched by name, never by position. */
    private static final Set<String> UUID_COLUMNS =
            Set.of("uuid", "account", "counterparty", "recipient", "sender", "player_uuid", "owner");

    private static final String USERS_TABLE = "users";

    /** Datasets an administrator may reset with /resetdbfields. */
    enum Group {
        ALL(),
        HOMES("homes"),
        KITS("kits"),
        PLAYTIME("playtime"),
        RANKS("ranks"),
        COOLDOWNS("cooldowns"),
        TELEPORT("teleport", "rtp"),
        MAIL("mail"),
        NICK("nick"),
        MODERATION("moderation"),
        ECONOMY("economy"),
        CHAT("chat"),
        AFK("afk"),
        WORTH("worth"),
        INVENTORIES("inventories"),
        SEEN("seen"),
        PLAYER("player", "playerplus", "vanish", "elytra", "staff", "effects", "particles", "sourbycraft");

        private final List<String> prefixes;

        Group(String... prefixes) {
            this.prefixes = List.of(prefixes);
        }

        boolean matches(String tableSuffix) {
            if (prefixes.isEmpty()) {
                return true;
            }
            for (String prefix : prefixes) {
                if (tableSuffix.equals(prefix) || tableSuffix.startsWith(prefix + "_")
                        || tableSuffix.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        static Group parse(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    record TableRef(String suffix, String table, List<String> uuidColumns) {}

    record PurgeResult(int tables, long rows) {}

    /** A users-table row read without loading the account into the user cache. */
    record AccountRow(UUID uuid, String name, long lastSeen) {}

    record DuplicateAccount(UUID uuid, String name, long lastSeen, boolean skipped) {}

    record DuplicateGroup(String name, UUID kept, List<DuplicateAccount> removed, List<DuplicateAccount> skipped) {
        DuplicateGroup {
            removed = List.copyOf(removed);
            skipped = List.copyOf(skipped);
        }
    }

    private final ModuleContext ctx;
    private final Database db;

    UserDataAdmin(ModuleContext ctx) {
        this.ctx = ctx;
        this.db = ctx.database();
    }

    // ---- table discovery -------------------------------------------------------------------------

    /** Resolves a stored account by name without touching the user cache (which would re-save deleted rows). */
    CompletableFuture<java.util.Optional<AccountRow>> account(String name) {
        String lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, name, last_seen FROM "
                    + db.table(USERS_TABLE) + " WHERE name_lower = ? ORDER BY last_seen DESC")) {
                ps.setString(1, lower);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        return java.util.Optional.<AccountRow>empty();
                    }
                    return java.util.Optional.of(new AccountRow(UUID.fromString(rows.getString(1)), rows.getString(2),
                            rows.getLong(3)));
                }
            }
        });
    }

    /** Tables under the configured prefix that carry at least one account-id column. */
    CompletableFuture<List<TableRef>> tables() {
        String prefix = db.table("");
        return db.query(connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = safeCatalog(connection);
            List<String> names = new ArrayList<>();
            try (ResultSet rows = meta.getTables(catalog, null, "%", new String[] {"TABLE"})) {
                while (rows.next()) {
                    String name = rows.getString("TABLE_NAME");
                    if (name == null || !name.startsWith(prefix) || !name.matches("[A-Za-z0-9_]+")) {
                        continue;
                    }
                    names.add(name);
                }
            }
            List<TableRef> refs = new ArrayList<>();
            for (String table : names) {
                String suffix = table.substring(prefix.length());
                List<String> uuidColumns = uuidColumns(connection, table);
                if (!uuidColumns.isEmpty()) {
                    refs.add(new TableRef(suffix, table, uuidColumns));
                }
            }
            return List.copyOf(refs);
        });
    }

    private static String safeCatalog(java.sql.Connection connection) {
        try {
            return connection.getCatalog();
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private static List<String> uuidColumns(java.sql.Connection connection, String table) throws java.sql.SQLException {
        List<String> columns = new ArrayList<>();
        // Portable and immune to metadata case/naming differences between SQLite and MariaDB.
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            ResultSetMetaData meta = rows.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                if (label != null && UUID_COLUMNS.contains(label.toLowerCase(Locale.ROOT))) {
                    columns.add(label);
                }
            }
        }
        columns.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(columns);
    }

    // ---- deletion --------------------------------------------------------------------------------

    /** Deletes every account-id row of one account in the selected datasets, and (optionally) the user row. */
    CompletableFuture<PurgeResult> purge(UUID uuid, Group group, boolean deleteUserRow) {
        String id = uuid.toString();
        return tables().thenCompose(refs -> db.transaction(connection -> {
            int tables = 0;
            long rows = 0;
            for (TableRef ref : refs) {
                if (!group.matches(ref.suffix())) {
                    continue;
                }
                // The user row is handled separately: some callers keep it (a field reset).
                if (ref.suffix().equals(USERS_TABLE)) {
                    continue;
                }
                long deleted = deleteAccountRows(connection, ref, id);
                if (deleted > 0) {
                    tables++;
                    rows += deleted;
                }
            }
            if (deleteUserRow) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM " + db.table(USERS_TABLE) + " WHERE uuid = ?")) {
                    ps.setString(1, id);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0) {
                        tables++;
                        rows += deleted;
                    }
                }
            }
            return new PurgeResult(tables, rows);
        }));
    }

    private static long deleteAccountRows(java.sql.Connection connection, TableRef ref, String id)
            throws java.sql.SQLException {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(ref.table()).append(" WHERE ");
        List<String> columns = ref.uuidColumns();
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : " OR ").append(columns.get(i)).append(" = ?");
        }
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < columns.size(); i++) {
                ps.setString(i + 1, id);
            }
            return ps.executeUpdate();
        }
    }

    // ---- duplicates ------------------------------------------------------------------------------

    /**
     * Finds names stored under more than one uuid. The row seen most recently is kept; every other account
     * is purged unless it is currently loaded (an online or cached account is reported instead).
     */
    CompletableFuture<List<DuplicateGroup>> duplicates() {
        return db.query(connection -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT name_lower FROM " + db.table("users")
                    + " GROUP BY name_lower HAVING COUNT(*) > 1 ORDER BY name_lower");
                    ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
            List<DuplicateGroup> groups = new ArrayList<>();
            for (String nameLower : names) {
                List<DuplicateAccount> accounts = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, name, last_seen FROM "
                        + db.table("users") + " WHERE name_lower = ? ORDER BY last_seen DESC")) {
                    ps.setString(1, nameLower);
                    try (ResultSet rows = ps.executeQuery()) {
                        while (rows.next()) {
                            UUID uuid = UUID.fromString(rows.getString(1));
                            accounts.add(new DuplicateAccount(uuid, rows.getString(2), rows.getLong(3),
                                    loaded(uuid)));
                        }
                    }
                }
                if (accounts.size() < 2) {
                    continue;
                }
                DuplicateAccount kept = accounts.getFirst();
                List<DuplicateAccount> removed = new ArrayList<>();
                List<DuplicateAccount> skipped = new ArrayList<>();
                for (int i = 1; i < accounts.size(); i++) {
                    DuplicateAccount account = accounts.get(i);
                    if (account.skipped()) {
                        skipped.add(account);
                    } else {
                        removed.add(account);
                    }
                }
                groups.add(new DuplicateGroup(kept.name(), kept.uuid(), removed, skipped));
            }
            return List.copyOf(groups);
        });
    }

    private boolean loaded(UUID uuid) {
        return ctx.users().cached(uuid).isPresent() || org.bukkit.Bukkit.getPlayer(uuid) != null;
    }

    /** Deletes one duplicate group's accounts (all datasets plus the user row). */
    CompletableFuture<PurgeResult> purgeDuplicates(DuplicateGroup group) {
        Collection<DuplicateAccount> targets = group.removed();
        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(new PurgeResult(0, 0));
        }
        CompletableFuture<PurgeResult> chain = CompletableFuture.completedFuture(new PurgeResult(0, 0));
        for (DuplicateAccount account : targets) {
            chain = chain.thenCompose(previous -> purge(account.uuid(), Group.ALL, true)
                    .thenApply(result -> new PurgeResult(previous.tables() + result.tables(),
                            previous.rows() + result.rows())));
        }
        return chain;
    }
}
