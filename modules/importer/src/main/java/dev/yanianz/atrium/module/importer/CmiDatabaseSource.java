package dev.yanianz.atrium.module.importer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only view of a CMI database. A separate one-connection Hikari pool is opened from CMI's own
 * settings; Atrium never writes to it and no CMI code or jar is loaded. SQLite and MySQL/MariaDB use
 * the same code path because only portable SQL is issued.
 */
final class CmiDatabaseSource implements AutoCloseable {
    private final CmiConnectionConfig config;
    private final HikariDataSource pool;
    private final boolean mysql;
    private final String usersTable;
    private final Map<String, String> columns;
    private final String idColumn;
    private final String uuidColumn;
    private final String nameColumn;
    private final String nickColumn;
    private final String homesColumn;
    private final String economyColumn;
    private final String playtimeTable;
    private final Map<String, String> playtimeColumns;

    private CmiDatabaseSource(CmiConnectionConfig config, HikariDataSource pool) throws SQLException {
        this.config = config;
        this.pool = pool;
        this.mysql = config.kind() == CmiConnectionConfig.Kind.MYSQL;
        try (Connection connection = pool.getConnection()) {
            this.usersTable = findTable(connection, "users");
            if (usersTable == null) {
                throw new SQLException("CMI users table not found in " + config.describe());
            }
            this.columns = columns(connection, usersTable);
            this.idColumn = pick("id");
            this.uuidColumn = pick("player_uuid", "uuid");
            this.nameColumn = pick("username", "player_name", "name", "playername");
            this.nickColumn = pick("nickname", "nick_name", "nick");
            this.homesColumn = pick("homes");
            this.economyColumn = pick("economy", "balance");
            if (uuidColumn == null) {
                throw new SQLException("CMI users table has no player_uuid/uuid column");
            }
            this.playtimeTable = findTable(connection, "playtime");
            this.playtimeColumns = playtimeTable == null ? Map.of() : columns(connection, playtimeTable);
        }
    }

    static CmiDatabaseSource open(CmiConnectionConfig config) throws SQLException {
        if (config.kind() == CmiConnectionConfig.Kind.SQLITE
                && (config.sqliteFile() == null || !Files.isRegularFile(config.sqliteFile()))) {
            throw new SQLException("CMI SQLite database not found: " + config.sqliteFile());
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("atrium-cmi-import");
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10_000);
        hikari.setValidationTimeout(5_000);
        hikari.setJdbcUrl(config.jdbcUrl());
        if (config.kind() == CmiConnectionConfig.Kind.SQLITE) {
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.addDataSourceProperty("busy_timeout", "5000");
            // The importer only issues SELECTs; do not set JDBC read-only because SQLite refuses it.
        } else {
            hikari.setDriverClassName("org.mariadb.jdbc.Driver");
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setReadOnly(true);
        }
        HikariDataSource pool = new HikariDataSource(hikari);
        try {
            return new CmiDatabaseSource(config, pool);
        } catch (SQLException | RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    String describe() {
        return config.describe();
    }

    boolean hasHomesColumn() {
        return homesColumn != null;
    }

    boolean hasNickColumn() {
        return nickColumn != null;
    }

    boolean hasEconomyColumn() {
        return economyColumn != null;
    }

    boolean hasPlaytimeTable() {
        return playtimeTable != null;
    }

    long userCount() throws SQLException {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + quote(usersTable))) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    List<CmiUserRecord> users(int offset, int limit) throws SQLException {
        List<String> selected = new ArrayList<>();
        if (idColumn != null) {
            selected.add(idColumn);
        }
        selected.add(uuidColumn);
        addIfPresent(selected, nameColumn);
        addIfPresent(selected, nickColumn);
        addIfPresent(selected, homesColumn);
        addIfPresent(selected, economyColumn);
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < selected.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(quote(selected.get(i)));
        }
        sql.append(" FROM ").append(quote(usersTable));
        sql.append(" ORDER BY ").append(quote(idColumn != null ? idColumn : uuidColumn));
        sql.append(" LIMIT ? OFFSET ?");
        List<CmiUserRecord> result = new ArrayList<>(limit);
        try (Connection connection = pool.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            if (mysql) {
                ps.setFetchSize(limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = idColumn == null ? 0 : rs.getLong(idColumn);
                    String rawUuid = value(rs, uuidColumn);
                    UUID uuid = null;
                    if (rawUuid != null) {
                        try {
                            uuid = UUID.fromString(rawUuid.trim());
                        } catch (IllegalArgumentException ignored) {
                            // Kept as rawUuid so the run can report it without writing anything.
                        }
                    }
                    result.add(new CmiUserRecord(id, uuid, rawUuid, value(rs, nameColumn),
                            value(rs, nickColumn), value(rs, homesColumn), value(rs, economyColumn)));
                }
            }
        }
        return result;
    }

    /**
     * Sums the playtime of the given CMI user ids. CMI stores milliseconds per hour bucket in
     * {@code h0..h23}; a {@code seconds} column is accepted too. Missing table or ids return an empty map.
     */
    Map<Long, Long> playtimeSeconds(Collection<Long> ids) throws SQLException {
        if (playtimeTable == null || ids.isEmpty()) {
            return Map.of();
        }
        String playerColumn = find(playtimeColumns, "player_id", "playerid", "player", "uuid");
        if (playerColumn == null) {
            return Map.of();
        }
        String secondsColumn = find(playtimeColumns, "seconds", "total_seconds", "totalseconds");
        List<String> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            String column = find(playtimeColumns, "h" + hour);
            if (column != null) {
                hours.add(column);
            }
        }
        if (secondsColumn == null && hours.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        List<Long> values = new ArrayList<>(ids);
        int chunk = 400;
        for (int from = 0; from < values.size(); from += chunk) {
            List<Long> part = values.subList(from, Math.min(from + chunk, values.size()));
            StringBuilder sql = new StringBuilder("SELECT ").append(quote(playerColumn));
            if (secondsColumn != null) {
                sql.append(", ").append(quote(secondsColumn));
            }
            for (String hour : hours) {
                sql.append(", ").append(quote(hour));
            }
            sql.append(" FROM ").append(quote(playtimeTable)).append(" WHERE ").append(quote(playerColumn))
                    .append(" IN (");
            for (int i = 0; i < part.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            sql.append(')');
            try (Connection connection = pool.getConnection();
                    PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < part.size(); i++) {
                    ps.setLong(i + 1, part.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(playerColumn);
                        long seconds = secondsColumn == null ? 0 : Math.max(0, rs.getLong(secondsColumn));
                        if (secondsColumn == null) {
                            long millis = 0;
                            for (String hour : hours) {
                                millis = saturatingAdd(millis, Math.max(0, rs.getLong(hour)));
                            }
                            seconds = millis / 1000L;
                        }
                        if (seconds > 0) {
                            result.merge(id, seconds, CmiDatabaseSource::saturatingAdd);
                        }
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        pool.close();
    }

    // ---- discovery ----

    private String findTable(Connection connection, String wanted) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();
        List<String> candidates = new ArrayList<>();
        try {
            collectTables(meta, catalog, candidates);
        } catch (SQLException first) {
            if (catalog == null) {
                throw first;
            }
            collectTables(meta, null, candidates);
        }
        String prefixed = config.kind() == CmiConnectionConfig.Kind.MYSQL && !config.tablePrefix().isEmpty()
                ? config.tablePrefix() + wanted
                : null;
        if (prefixed != null) {
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(prefixed)) {
                    return candidate;
                }
            }
        }
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).endsWith(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    private static void collectTables(DatabaseMetaData meta, String catalog, List<String> out) throws SQLException {
        try (ResultSet rs = meta.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null) {
                    out.add(name);
                }
            }
        }
    }

    private Map<String, String> columns(Connection connection, String table) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT * FROM " + quote(table) + " LIMIT 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                if (label == null || label.isBlank()) {
                    label = meta.getColumnName(i);
                }
                if (label != null && !label.isBlank()) {
                    result.put(label.toLowerCase(Locale.ROOT), label);
                }
            }
        }
        return result;
    }

    private String pick(String... names) {
        return find(columns, names);
    }

    private static String find(Map<String, String> map, String... names) {
        for (String name : names) {
            String value = map.get(name.toLowerCase(Locale.ROOT));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void addIfPresent(List<String> selected, String column) {
        if (column != null && !selected.contains(column)) {
            selected.add(column);
        }
    }

    private static String value(ResultSet rs, String column) throws SQLException {
        return column == null ? null : rs.getString(column);
    }

    private String quote(String identifier) {
        return mysql ? "`" + identifier.replace("`", "``") + "`" : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        if (((left ^ sum) & (right ^ sum)) < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }
}
